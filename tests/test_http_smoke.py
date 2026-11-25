"""Standard-library HTTP smoke tests for the Jess server.

Run from repository root after `mvn compile`:
    python3 -m unittest discover -s tests -p 'test_*.py'
"""
import http.client
import socket
import subprocess
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PORT = 4221


class HttpSmokeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        classes = ROOT / "target" / "classes"
        if not (classes / "jess" / "Jess.class").exists():
            raise RuntimeError("Run 'mvn compile' before the smoke tests")

        cls.process = subprocess.Popen(
            ["java", "jess.Jess"],
            cwd=classes,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        for _ in range(60):
            if cls.process.poll() is not None:
                raise RuntimeError("Jess terminated unexpectedly during startup")
            try:
                with socket.create_connection(("127.0.0.1", PORT), timeout=0.2):
                    return
            except OSError:
                time.sleep(0.1)
        cls.process.terminate()
        cls.process.wait(timeout=5)
        raise RuntimeError("Jess did not begin listening on port 4221")

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, "process") and cls.process.poll() is None:
            cls.process.terminate()
            try:
                cls.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                cls.process.kill()
                cls.process.wait(timeout=5)

    def request(self, path, method="GET"):
        conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=3)
        try:
            conn.request(method, path)
            response = conn.getresponse()
            body = response.read()
            return response.status, dict(response.getheaders()), body
        finally:
            conn.close()

    def test_index_and_assets(self):
        for path, expected in [
            ("/", b"<html"),
            ("/chessboard.js", b"WebSocket"),
            ("/chessboard.css", b"chessboard"),
            ("/pieces.css", b"whiteKing"),
        ]:
            with self.subTest(path=path):
                status, headers, body = self.request(path)
                self.assertEqual(status, 200)
                self.assertIn(expected, body)
                self.assertEqual(int(headers["Content-Length"]), len(body))
                self.assertEqual(headers["Connection"].lower(), "close")

    def test_unknown_file_returns_404(self):
        status, _, _ = self.request("/does-not-exist.txt")
        self.assertEqual(status, 404)

    def test_sensitive_file_not_served(self):
        for path in ["/pom.xml", "/%2e%2e/pom.xml", "/static/../../pom.xml"]:
            with self.subTest(path=path):
                status, _, _ = self.request(path)
                self.assertEqual(status, 404)

    def test_post_is_rejected(self):
        status, _, _ = self.request("/", method="POST")
        self.assertEqual(status, 405)

    def test_empty_disconnect_does_not_break_server(self):
        with socket.create_connection(("127.0.0.1", PORT), timeout=1):
            pass
        status, _, body = self.request("/")
        self.assertEqual(status, 200)
        self.assertIn(b"<html", body)


if __name__ == "__main__":
    unittest.main()
