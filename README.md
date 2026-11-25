# Jess

Jess is an experimental multiplayer chess **networking prototype** built with Java 21 and vanilla JavaScript. Its central technical work is a custom HTTP static-file server and a custom WebSocket implementation using the Java standard library.

It serves an interactive browser chessboard, assigns white/black/spectator roles, and broadcasts move messages. **It does not validate chess moves, enforce turn order on the server, or maintain an authoritative chess position.** Jess is a local educational demonstration, not a completed chess service.

## Implemented

| Area | Current behavior |
| --- | --- |
| HTTP | Small, single-request static server with bounded request headers, timeouts, and an asset allowlist |
| WebSocket | Upgrade handshake, masked input frames, extended lengths, fragmented text, ping/pong and close frames |
| Sessions | Up to ten concurrent client connections, with capacity and roles reclaimed on disconnect |
| UI | Chessboard, Unicode pieces, click-to-move, role display, synchronized broadcasts |
| Messages | Legacy colon-separated text: coordinate syntax checked; **chess legality not checked** |
| Tests | HTTP and raw-WebSocket Python integration checks; Playwright Chromium browser smoke test |

## Run locally

Requirements: Java 21 and Maven.

```sh
git clone https://github.com/Khemio/jess.git
cd jess
mvn compile
./run.sh
```

The included script changes to `target/classes` and runs `java jess.Jess`. On systems without a POSIX shell, run these two commands manually. The current static-file handler expects `static/` relative to the **process working directory**, so do not run the class from the repository root.

Open **http://127.0.0.1:4221/**, then open a second browser tab. The first two successful connections occupy white and black seats; other connections are spectators. A closed connection releases its role. Try white `e2e4` followed by black `e7e5` through the UI.

**Limitations:** the server relays moves without chess rules or turn validation. New spectators and refreshed clients start from the original board, not the current state. The client uses a hard-coded local WebSocket URL, and both Java servers listen on loopback.

| Port | Purpose |
| --- | --- |
| `4221` | HTTP files: `/`, `/chessboard.js`, `/chessboard.css`, `/pieces.css` |
| `4220` | WebSocket upgrade at `/game` |

## Testing

From the repository root, first run `mvn compile`. Then:

```sh
python3 -m unittest discover -s tests -p 'test_http_smoke.py' -v
python3 -m unittest discover -s tests -p 'test_websocket_smoke.py' -v
```

The optional real-browser smoke test also requires Playwright and its Chromium browser:

```sh
python3 -m pip install playwright==1.56.0
python3 -m playwright install chromium
python3 -m unittest discover -s tests -p 'test_browser_smoke.py' -v
```

The browser test checks that two Chromium pages can exchange `e2e4` and `e7e5` and that a third connection receives the spectator role. CI builds and runs all integration tests and publishes a screenshot artifact when available. Tests start and stop their own Java server, so close any manually running Jess instance before executing them.

## Design

```text
Browser (HTML/CSS/JavaScript)
      |             |
  HTTP :4221   WebSocket :4220
      |             |
Static files    Frame decoder / handshake
                    |
             Role registry / broadcast
```

The current application messages are text such as `role:white`, `move:e2e4`, and `move:e2e4:black`. This is **not** the proposed versioned JSON protocol. See [PROTOCOL.md](docs/PROTOCOL.md) for that future design.

Relevant implementation directories:

- `src/main/java/jess/`: starts the HTTP and WebSocket listeners
- `src/main/java/server/http/`: narrowly scoped static-file HTTP handling
- `src/main/java/server/websocket/`: custom WebSocket framing and connection management
- `src/main/resources/static/`: browser UI
- `tests/`: Python transport and browser integration tests
- `.github/workflows/`: Java build and test CI

The earlier `server.http.Request` class is retained for reference but is not used by the current HTTP handler.

## Further documentation

[Architecture](docs/ARCHITECTURE.md) · [Known issues](docs/KNOWN_ISSUES.md) · [Roadmap](docs/ROADMAP.md) · [Proposed JSON protocol](docs/PROTOCOL.md)

C++ chess-engine integration, a C++ server, or a C++/SDL3/WebAssembly 3D board remain optional future directions. The current goal is a stable, presentable networking demonstration.

**Security:** Jess has no authentication, HTTPS/WSS, origin policy, or game-level authorization. The listeners bind to `127.0.0.1` and should not be publicly exposed.
