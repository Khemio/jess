"""End-to-end smoke test with a real browser (Playwright).

Run after compiling:
    python -m playwright install chromium
    python -m unittest tests/test_browser_smoke.py -v

This test verifies browser interaction and message broadcasting, NOT chess legality.
"""
import http.client
import socket
import subprocess
import time
import unittest
from pathlib import Path

from playwright.sync_api import expect, sync_playwright

ROOT = Path(__file__).resolve().parents[1]
BASE_URL = "http://127.0.0.1:4221/"


def square(page, file, rank):
    """Locate a board cell by algebraic coordinate, without changing the frontend."""
    col = "abcdefgh".index(file)
    row = 8 - rank
    return page.locator("table.chessboard tbody tr").nth(row).locator("td").nth(col)


class BrowserSmokeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        classes = ROOT / "target" / "classes"
        if not (classes / "jess" / "Jess.class").is_file():
            raise RuntimeError("Run 'mvn compile' first")

        cls.process = subprocess.Popen(
            ["java", "jess.Jess"],
            cwd=classes,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        for _ in range(100):
            if cls.process.poll() is not None:
                raise RuntimeError("Jess exited during startup")
            try:
                with socket.create_connection(("127.0.0.1", 4221), timeout=0.2), \
                     socket.create_connection(("127.0.0.1", 4220), timeout=0.2):
                    break
            except OSError:
                time.sleep(0.1)
        else:
            cls.process.terminate()
            cls.process.wait(timeout=5)
            raise RuntimeError("Jess servers did not start")

    @classmethod
    def tearDownClass(cls):
        if cls.process.poll() is None:
            cls.process.terminate()
            try:
                cls.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                cls.process.kill()
                cls.process.wait(timeout=5)

    def test_two_browser_players_exchange_moves(self):
        errors = []
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True)
            try:
                white = browser.new_page()
                black = browser.new_page()
                white.on("pageerror", lambda e: errors.append(str(e)))
                black.on("pageerror", lambda e: errors.append(str(e)))

                white.goto(BASE_URL)
                expect(white.locator("#player")).to_have_text("White")
                black.goto(BASE_URL)
                expect(black.locator("#player")).to_have_text("Black")

                # White sends e2e4; both browsers render the same pawn at e4.
                square(white, "e", 2).click()
                square(white, "e", 4).click()
                for page in (white, black):
                    expect(square(page, "e", 4)).to_have_attribute("data-piece", "whitePawn")
                    expect(square(page, "e", 2)).not_to_have_attribute("data-piece", "whitePawn")

                # Black replies e7e5; both boards receive the broadcast.
                square(black, "e", 7).click()
                square(black, "e", 5).click()
                for page in (white, black):
                    expect(square(page, "e", 5)).to_have_attribute("data-piece", "blackPawn")
                    expect(square(page, "e", 7)).not_to_have_attribute("data-piece", "blackPawn")

                # A third browser can connect as a spectator.
                spectator = browser.new_page()
                spectator.on("pageerror", lambda e: errors.append(str(e)))
                spectator.goto(BASE_URL)
                expect(spectator.locator("#player")).to_have_text("Spectator")

                # An optional visual artifact for evaluating the minimal UI.
                white.screenshot(path=str(ROOT / "target" / "browser-demo.png"), full_page=True)
                self.assertEqual(errors, [], "Unexpected browser JavaScript errors")
            finally:
                browser.close()


if __name__ == "__main__":
    unittest.main()
