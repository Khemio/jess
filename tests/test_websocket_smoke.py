"""Integration tests for the custom WebSocket transport.

No third-party dependencies are required. The raw TCP client deliberately sends
frames in fragments so the server cannot rely on packet boundaries.
"""
import base64
import os
import socket
import struct
import subprocess
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PORT = 4220


def read_exact(sock, count):
    result = bytearray()
    while len(result) < count:
        chunk = sock.recv(count - len(result))
        if not chunk:
            raise EOFError("Socket closed while reading frame")
        result.extend(chunk)
    return bytes(result)


def recv_frame(sock):
    head = read_exact(sock, 2)
    fin = bool(head[0] & 0x80)
    opcode = head[0] & 0x0F
    length = head[1] & 0x7F
    if length == 126:
        length = struct.unpack("!H", read_exact(sock, 2))[0]
    elif length == 127:
        length = struct.unpack("!Q", read_exact(sock, 8))[0]
    if head[1] & 0x80:
        raise AssertionError("Server must not mask outgoing frames")
    return fin, opcode, read_exact(sock, length)


def masked_frame(opcode, payload=b"", fin=True):
    mask = b"\x11\x22\x33\x44"
    length = len(payload)
    header = bytes([(0x80 if fin else 0) | opcode])
    if length < 126:
        header += bytes([0x80 | length])
    elif length < 65536:
        header += b"\xfe" + struct.pack("!H", length)
    else:
        header += b"\xff" + struct.pack("!Q", length)
    return header + mask + bytes(v ^ mask[i % 4] for i, v in enumerate(payload))


def connect_websocket():
    sock = socket.create_connection(("127.0.0.1", PORT), timeout=3)
    sock.settimeout(3)
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    request = (
        "GET /game HTTP/1.1\r\n"
        "Host: localhost:4220\r\n"
        "Upgrade: websocket\r\n"
        "Connection: keep-alive, Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n\r\n"
    )
    sock.sendall(request.encode("ascii"))
    header = bytearray()
    while not header.endswith(b"\r\n\r\n"):
        header.extend(read_exact(sock, 1))
        if len(header) > 8192:
            raise AssertionError("Upgrade response is unbounded")
    if not header.startswith(b"HTTP/1.1 101 "):
        raise AssertionError(f"WebSocket upgrade failed: {header!r}")
    return sock


class WebSocketSmokeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        classes = ROOT / "target" / "classes"
        if not (classes / "jess" / "Jess.class").exists():
            raise RuntimeError("Run 'mvn compile' before the WebSocket tests")
        cls.process = subprocess.Popen(
            ["java", "jess.Jess"],
            cwd=classes,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        for _ in range(60):
            if cls.process.poll() is not None:
                raise RuntimeError("Jess exited before starting")
            try:
                with socket.create_connection(("127.0.0.1", PORT), timeout=0.2):
                    return
            except OSError:
                time.sleep(0.1)
        cls.process.terminate()
        cls.process.wait(timeout=5)
        raise RuntimeError("WebSocket listener not available")

    @classmethod
    def tearDownClass(cls):
        if cls.process.poll() is None:
            cls.process.terminate()
            try:
                cls.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                cls.process.kill()
                cls.process.wait(timeout=5)

    def test_move_broadcast_and_client_masking(self):
        with connect_websocket() as white, connect_websocket() as black:
            self.assertEqual(recv_frame(white)[2], b"role:white")
            self.assertEqual(recv_frame(black)[2], b"role:black")

            data = masked_frame(1, b"move:e2e4")
            white.sendall(data[:1])
            white.sendall(data[1:5])
            white.sendall(data[5:])

            for client in (white, black):
                self.assertEqual(recv_frame(client), (True, 1, b"move:e2e4:black"))

    def test_fragmented_message_and_ping(self):
        with connect_websocket() as white:
            self.assertEqual(recv_frame(white)[2], b"role:white")
            white.sendall(masked_frame(1, b"move:e", fin=False))
            white.sendall(masked_frame(9, b"hello"))
            self.assertEqual(recv_frame(white), (True, 10, b"hello"))
            white.sendall(masked_frame(0, b"2e4", fin=True))
            self.assertEqual(recv_frame(white)[2], b"move:e2e4:black")

    def test_invalid_unmasked_client_frame_gets_protocol_close(self):
        with connect_websocket() as white:
            self.assertEqual(recv_frame(white)[2], b"role:white")
            white.sendall(b"\x81\x04test")
            fin, opcode, payload = recv_frame(white)
            self.assertEqual((fin, opcode), (True, 8))
            self.assertEqual(struct.unpack("!H", payload[:2])[0], 1002)

    def test_invalid_upgrade_is_rejected(self):
        with socket.create_connection(("127.0.0.1", PORT), timeout=3) as sock:
            sock.settimeout(3)
            sock.sendall(
                b"GET /game HTTP/1.1\r\nHost: localhost\r\n"
                b"Upgrade: websocket\r\nConnection: Upgrade\r\n\r\n"
            )
            self.assertTrue(sock.recv(256).startswith(b"HTTP/1.1 400 "))

    def test_seat_is_reclaimed_after_disconnect(self):
        white = connect_websocket()
        self.assertEqual(recv_frame(white)[2], b"role:white")
        white.sendall(masked_frame(8, struct.pack("!H", 1000)))
        self.assertEqual(recv_frame(white)[1], 8)
        white.close()
        # The handler closes the connection before its finally block releases the role.
        time.sleep(0.1)
        with connect_websocket() as replacement:
            self.assertEqual(recv_frame(replacement)[2], b"role:white")

    def test_connection_count_does_not_accumulate(self):
        for _ in range(12):
            with connect_websocket() as client:
                self.assertEqual(recv_frame(client)[2], b"role:white")
                client.sendall(masked_frame(8, struct.pack("!H", 1000)))
                self.assertEqual(recv_frame(client)[1], 8)
            time.sleep(0.02)


if __name__ == "__main__":
    unittest.main()
