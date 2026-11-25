# Jess architecture — current cleanup branch

Jess runs two independent Java 21 TCP listeners, one for HTTP assets and one for WebSocket connections. It has no server-side chess board or rules engine.

## HTTP

`HttpServer` accepts connections on loopback port `4221` and launches a `ClientHandler` thread for each accepted socket. The handler uses blocking reads with a ten-second timeout and an 8 KiB header limit. It accepts one GET request per connection, allows exactly the four application assets, writes a byte-accurate `Content-Length`, and closes the socket. This is a minimal static-file server, **not** a general HTTP/1.1 implementation. The historical `Request.java` parser is unused.

## WebSocket

`WebSocketServer` accepts connections on loopback port `4220` with a semaphore limiting simultaneous connections to ten, including pending handshakes. It manages a concurrent set of sessions. After a valid WebSocket upgrade for `/game`, `WebSocketHandler` registers an available white or black seat; subsequent connections become spectators. Disconnected handlers free their seats and capacity.

`Frame.read(InputStream)` parses WebSocket frames independently of TCP chunk boundaries, reads extended lengths, enforces client masking, decodes payloads, and rejects malformed, oversized or unsupported frames. It imposes an 8 KiB frame limit. The handler assembles fragmented text messages with an 8 KiB aggregate limit, processes ping/pong and close frames, and validates UTF-8. Writes use a per-connection synchronization lock so concurrent broadcast threads do not interleave frame bytes.

This is a narrow, custom WebSocket implementation, **not a fully audited RFC 6455 stack**. Idle connections use a socket read timeout, and no server-originated heartbeat is implemented.

## Browser and application messaging

The vanilla JavaScript UI renders an 8×8 table with piece names in `data-piece` attributes and Unicode chess symbols supplied through CSS. It connects to `ws://localhost:4220/game`.

The current messages are:

| Direction | Example |
| --- | --- |
| Server to client | `role:white` / `role:black` / `role:spectator` |
| Client to server | `move:e2e4` |
| Server broadcast | `move:e2e4:black` |

Move requests are checked only for syntax. The server does not know the position, enforce turns, check legal moves, or send new arrivals a board snapshot. The browser itself maintains a `canMove` flag and updates the DOM on broadcasts, so clients joining late may disagree about the position.

## Concurrency and tests

The HTTP and WebSocket servers each have an acceptor thread plus a per-client handler thread. WebSocket connections are capped by a semaphore; active sessions are stored in a concurrent set; role allocation is synchronized; broadcasts use an active-session snapshot and per-client synchronized writes.

Python integration tests cover HTTP resource requests and raw WebSocket framing/handshake/role reuse. A Playwright Chromium smoke test checks two browser windows exchanging moves and a spectator role. They are networking/UI tests, **not chess correctness tests**.

See [KNOWN_ISSUES.md](KNOWN_ISSUES.md) for limitations, [ROADMAP.md](ROADMAP.md) for optional further development, and [PROTOCOL.md](PROTOCOL.md) for a proposed JSON application protocol that is **not yet implemented**.
