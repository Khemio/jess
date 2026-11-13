# Known issues, limitations, and next steps

See [the development roadmap](ROADMAP.md) for a sequenced plan to address these issues and [the proposed protocol](PROTOCOL.md) for the planned client/server message contract.

These findings are based on the Jess `main` branch source as reviewed in November 2026. They are intended to distinguish the current educational prototype from a robust multiplayer chess service. They have **not** been independently reproduced with runtime tests.

## Current limitations at a glance

| Priority | Area | Source-backed limitation | Suggested next action |
| --- | --- | --- | --- |
| P0 | Chess integrity | Server broadcasts player messages without validating chess moves or turns | Introduce authoritative `GameState` and legal-move checks |
| P0 | Network framing | One `read()` into a fixed 1,024-byte buffer is treated as a complete WebSocket frame | Buffer bytes and parse complete frames incrementally |
| P0 | Connection capacity | Only 10 handler slots; the incrementing connection ID can index outside the arrays | Manage sessions in a bounded concurrent collection with cleanup |
| P1 | Client lifecycle | Closed handlers remain in the broadcast array; roles are not reassigned | Remove clients on disconnect and define reconnect behavior |
| P1 | HTTP CPU usage | `ClientHandler.run()` loops indefinitely, polling `available()` | Use blocking or buffered request reads and close idle sockets |
| P1 | Protocol validation | HTTP and WebSocket handshake inputs receive minimal validation | Validate methods, headers, request targets and WebSocket upgrades |
| P1 | Frame correctness | Fragmented, partial, large and batched frames are not handled robustly | Add stateful RFC 6455 decoding and protocol tests |
| P1 | Shared socket writes | Multiple handler threads can write to the same client stream | Serialize outgoing frames per connection |
| P2 | Rejoining / spectators | New connections only receive their role, not the current board | Send an authoritative snapshot on join |
| P2 | Automated testing | No automated test source tree is present | Add parser, framing, gameplay and integration tests |
| P2 | Configuration | Ports and `localhost` WebSocket URL are hardcoded | Externalize listener ports and compute client endpoint |
| P2 | Deployment | No TLS, authentication or abuse controls | Keep local-only until security and protocol hardening are complete |

## 1. Server-side chess validation is absent

**Relevant code:** `server/websocket/WebSocketHandler.java`, `static/chessboard.js`.

`handleMessage` logs an incoming string and broadcasts that string plus a next-role suffix. It does not:
- check whether a message is structurally a valid move;
- verify that the sending client should move next;
- verify piece ownership or the origin square;
- validate movement, captures, blocking, check, checkmate, castling, promotion, or en passant;
- update a server-owned chessboard.

The browser enforces only two lightweight conditions: its local `canMove` flag and matching color on the first clicked square. Those checks are neither sufficient to implement chess nor enforceable against a modified WebSocket client.

**Consequence:** Two clients may display moves that are illegal or inconsistent with a valid game. The WebSocket layer provides a move relay, not an authoritative game server.

**Fix direction:** Model chess state in Java; accept typed moves; apply only legal moves to one game session; broadcast the resulting accepted move or state revision.

## 2. Incoming WebSocket frames are not read safely

**Relevant code:** `WebSocketHandler.recv()`, `Frame(byte[])`.

`recv()` does the equivalent of:

```java
byte[] encoded = new byte[1024];
in.read(encoded);
Frame frame = new Frame(encoded);
```

This assumes an individual socket read contains a full frame. TCP provides a byte stream, not frame boundaries. One read can return fewer bytes than needed, and one read can also contain multiple messages. The return value of `read()` is ignored. The decoder can therefore read unreceived bytes, misinterpret content, or throw on malformed/short inputs.

Additional frame limitations include:
- Fragmentation is explicitly marked TODO.
- The extended length path for 64-bit payload lengths is marked untested and stores the value in an `int`.
- The decoder effectively assumes a masked nonempty incoming frame; unmasked frames are not decoded safely.
- No full validation of RSV flags, opcode rules or frame-length limits.
- Some error paths and unexpected frame data can throw unchecked exceptions instead of cleanly closing the connection.

**Fix direction:** Build a stateful incremental frame parser over a buffered input stream; enforce RFC 6455 client masking, limits and fragmentation rules; test boundary lengths and network chunking.

## 3. Connection slots are never reclaimed

**Relevant code:** `WebSocketServer.java`, `WebSocketHandler.java`.

The server stores clients in two arrays of length 10. The connection counter advances as new clients arrive. There is no code that removes an individual disconnected handler or assigns a free slot to a new connection. After enough connection attempts, the server can index beyond the end of its arrays.

The first two accepted connections receive the player roles, and later ones receive spectator. A browser reload or disconnect does not free a player role.

**Fix direction:** Manage each connection with a lifecycle-aware session registry, clean up in `finally`, and model player-seat ownership separately from connection sequence numbers.

## 4. HTTP request handling is a minimal prototype

**Relevant code:** `ClientHandler.java`, `Request.java`.

- The loop returns immediately if fewer than four bytes are available, then retries with no waiting.
- On some I/O failures, the outer loop continues indefinitely.
- The parser assumes a well-formed request line and headers separated by `: `.
- Successful responses calculate `Content-Length` using string character count rather than UTF-8 encoded bytes.
- The static-file resolver and error paths do not robustly handle missing files, query strings, malformed paths, or HTTP method errors.
- The server advertises keep-alive but lacks a well-defined connection timeout and shutdown lifecycle.

**Fix direction:** Block waiting for request bytes, parse within explicit size/time limits, validate paths and methods, and return coherent HTTP error responses. Calculate response length from `byte[]`.

## 5. WebSocket handshake validation is incomplete

**Relevant code:** `WebSocketHandler.handleReq()`.

A proper upgrade should verify the HTTP method, path, `Upgrade` and `Connection` headers, supported WebSocket version, and valid key before switching protocols. The current handler primarily checks `/game` and whether `Sec-WebSocket-Key` exists. If no key is present, execution can still proceed to application-level role sending.

**Fix direction:** Reject invalid upgrade requests with clear HTTP error responses and do not enter the WebSocket message loop until the handshake has succeeded.

## 6. Message delivery and ordering are not protected

**Relevant code:** `WebSocketHandler.send()`, `brodcast()`, `WebSocketServer.clients`.

Broadcasting walks the shared handler array. Two active handler threads can attempt to write frames to the same client's `OutputStream` concurrently, without a single per-connection writer or output queue. Exceptions during sends do not remove failed connections.

**Fix direction:** Give each connection a synchronized writer or serialized outgoing queue, and remove failed sessions.

## 7. Browser state is not synchronized on join

**Relevant code:** `static/chessboard.html`, `static/chessboard.js`, `WebSocketHandler.java`.

The HTML always starts with the initial board. A newly joining spectator receives only a role. If moves have already happened, the new spectator sees the starting position rather than the live board. There is also no message sequence number or resynchronization procedure.

**Fix direction:** On joining, send a canonical board snapshot and turn information. Consider FEN or another explicit board representation for the game state.

## 8. Other implementation caveats

- `chessboard.js` uses a hardcoded `ws://localhost:4220/game`; this fails for a browser on another machine without modification.
- The page does not provide user-visible feedback for most failed move selections or transport errors.
- Server-side `stop()` methods do not fully manage socket closure and handler shutdown; the application has no coordinated graceful shutdown.
- WebSocket encoding and decoding rely in places on platform-default text encodings instead of explicit UTF-8.
- The current system models a single shared broadcast audience, not independent games or rooms.
- No automated tests or CI configuration were observed in the reviewed tree.
- There is no `wss://`, HTTPS, authentication or authorization model.

## Recommended implementation order

**Milestone 1 — Make transport behavior reliable.** Fix the blocking HTTP loop, parse WebSocket frames incrementally, handle close/disconnect correctly, and add bounded connection management. Write unit tests for HTTP request parsing and WebSocket framing, plus a smoke test that loads the page.

**Milestone 2 — Make the server authoritative.** Introduce Java chess state, move parsing, turn tracking and legal-move validation. Reject invalid moves rather than rebroadcasting them. Add tests using known chess positions.

**Milestone 3 — Make multiplayer sessions coherent.** Separate games from sockets, manage seats and spectators, send state snapshots on join, and define reconnection and disconnection behavior.

**Milestone 4 — Improve developer experience.** Externalize configuration, introduce structured error messages, add logging and automated build/test checks, and decide whether to retain or replace the educational networking implementation.

**Milestone 5 — Consider deployment hardening.** Only after the prior milestones: input limits, authentication, transport security, rate limiting, and production-style observability.

## Overall assessment

Jess already provides a concrete example of the HTTP-to-browser asset flow, independent WebSocket handshakes, manual frame encoding/decoding, threaded client handling, and real-time message broadcasting. Its main missing capability is not visual chess interaction but **server-owned, validated game state**.

Until that component exists, describe Jess as a **networking-focused multiplayer chess prototype**, not as a completed online chess platform.