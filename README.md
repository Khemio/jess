# Jess

**Jess** is an experimental browser-based chess project written in Java and vanilla JavaScript. Its main focus is the networking layer: a small HTTP/1.1 static-file server and a WebSocket server implemented without a web framework.

The prototype displays a chessboard, assigns the first two WebSocket connections the white and black roles, and broadcasts submitted moves to connected browsers. **It is not yet a complete or rules-enforcing chess game.** The current server relays move messages without checking chess legality, whose turn it is, or the current board position.

> **Status:** Educational prototype / work in progress. Not suitable for public deployment or competitive games.

## Features and implementation status

| Area | Current status |
| --- | --- |
| Browser chessboard | Implemented: starting position, piece symbols, click-to-move interaction |
| Custom HTTP server | Implemented: serves the included HTML, CSS, and JavaScript assets |
| WebSocket connection | Implemented: dedicated listener, upgrade handshake, basic frame handling |
| Role assignment | Implemented: first connection is white, second is black, later connections are spectators |
| Move synchronization | Implemented: messages sent by a player are rebroadcast to connected clients |
| Turn handling | Client-side only; not enforced by the server |
| Chess rules / legal move validation | Not implemented |
| Authoritative board state | Not implemented |
| Game rooms, reconnects, saved games | Not implemented |
| Automated tests | No test suite is present in the reviewed repository |

This project is most useful as a demonstration of socket programming, application-protocol handling, basic concurrency, and browser/server messaging.

## Technology

- **Java 21** — socket servers, connection handlers, HTTP request parsing, WebSocket framing
- **Maven** — compilation
- **HTML, CSS, vanilla JavaScript** — chessboard UI and browser WebSocket client
- **Java standard library** — no additional runtime libraries declared in `pom.xml`

## Getting started

### Prerequisites

Install a JDK that supports **Java 21** and **Maven**.

### Build and run

```bash
git clone https://github.com/Khemio/jess.git
cd jess
mvn compile
./run.sh
```

The included `run.sh` script is intended for a POSIX-compatible shell. Its contents effectively run:

```bash
cd target/classes
java jess.Jess
```

You can use those two commands directly when not using the shell script. **Run from `target/classes`**: the HTTP server currently resolves static assets relative to its working directory.

Then open:

```text
http://localhost:4221/
```

For a basic local demonstration, open a second browser tab or window. Connections are assigned roles in arrival order. A third connection becomes a spectator. Restart the server to reset role assignments; disconnected clients are not yet reclaimed correctly.

### Ports

| Port | Service | Purpose |
| --- | --- | --- |
| `4221` | HTTP | Serves the browser UI and static assets |
| `4220` | WebSocket | Accepts connections at `/game` and relays player messages |

The WebSocket listener is **separate from the HTTP listener**. It is not an upgrade of the connection to port `4221`. The bundled browser client connects to `ws://localhost:4220/game`.

## How a move works

1. A browser connects to `/game` over WebSocket and receives its role, for example `role:white`.
2. The player clicks one of their pieces and then a destination square.
3. The browser sends a message such as `move:e2e4`.
4. The WebSocket handler relays the message to connected clients, appending the next role: `move:e2e4:black`.
5. Each browser updates its displayed board and its local turn flag.

Moves use four-character coordinate notation: an origin square followed by a destination square, for example `e2e4`. This is a **message format**, not evidence that the move has been validated. The UI checks only that the selected starting square contains a piece of the assigned color; it does not calculate legal moves.

The application also uses text messages such as `role:white`, `role:black`, and `role:spectator`. See [Architecture](docs/ARCHITECTURE.md) for the protocol and message flow.

## Repository layout

```text
src/main/
├── java/
│   ├── jess/
│   │   └── Main.java
│   └── server/
│       ├── http/
│       │   ├── HttpServer.java
│       │   ├── ClientHandler.java
│       │   └── Request.java
│       └── websocket/
│           ├── WebSocketServer.java
│           ├── WebSocketHandler.java
│           ├── Frame.java
│           └── Enums.java
└── resources/
    └── static/
        ├── chessboard.html
        ├── chessboard.js
        ├── chessboard.css
        └── pieces.css
```

`Main.java` defines the application entry-point class `jess.Jess`. It starts the two server listeners on separate threads.

## Documentation

- [Architecture and protocol](docs/ARCHITECTURE.md) — components, concurrency, HTTP/WebSocket behavior, and browser message flow
- [Known issues and next steps](docs/KNOWN_ISSUES.md) — current limitations and technical findings
- [Roadmap](docs/ROADMAP.md) — prioritized path to a reliable and playable local chess game
- [Proposed WebSocket protocol v1](docs/PROTOCOL.md) — draft JSON message contract; not implemented yet

## Development direction

The immediate goal is a **presentable local chess prototype**, not a larger Java chess platform. The priorities are reliable HTTP/WebSocket handling, a formal client/server message format, server-authoritative chess validation, reproducible tests, and a working two-player demonstration. See the [roadmap](docs/ROADMAP.md).

A future C++ chess engine (through IPC, a service, or JNI), a C++ backend rewrite, and a 3D C++/SDL3/WebAssembly board are potential follow-up experiments, **not part of the current implementation or MVP requirements**. The technology direction remains undecided.

## Scope and safety

Jess currently has no authentication, encryption, server-side turn verification, authoritative game state, or defensive handling for several malformed network inputs. It is intended for **local experimentation**, not for untrusted networks or production use.

The documentation describes the source as it exists on `main` at commit `81bce506fd7cea32b51b279b1dde69590fa06844` (reviewed November 2026). It is based on source inspection; no automated build or end-to-end run was performed as part of this documentation pass.