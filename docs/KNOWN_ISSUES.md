# Known issues — cleanup branch

The `cleanup/cv-ready` branch fixes the former HTTP busy loop, single-read WebSocket frame assumption, fixed-index client array overflow, and concurrent writes to client streams. Transport regression tests have been added. The following limits remain.

## Missing chess behavior (major)

The server is a **syntactically constrained move relay**, not a chess arbiter. It accepts `move:<from><to>` from either player seat without knowing the board or enforcing turn order. It cannot reject illegal movement, check, castling, en passant or invalid promotion. The UI has only lightweight client-side turn and piece-color checks.

**Future:** authoritative game state, legal-move validation, and explicit accept/reject messages.

## Late join and reload

New players and spectators start with the initial HTML board regardless of moves already broadcast. Reconnection releases and reacquires roles but does not restore game state. There are no game sessions or game-state revisions.

**Future:** snapshots on join and reconciliation/reconnect behavior.

## Application message format

Text messages such as `role:white` and `move:e2e4:black` have only minimal application validation. No message ID, game ID, version field, server acknowledgement, or structured rejection exists.

**Future:** the versioned JSON contract in [PROTOCOL.md](PROTOCOL.md), which is a proposal, not active code.

## Narrow transport scope

- HTTP: only four assets and one request per connection, not a complete general HTTP parser.
- WebSocket: fixed 8 KiB message limit, ten concurrent connections, incomplete standards conformance coverage, no extension negotiation or heartbeat.
- Per-connection threads are bounded only on the WebSocket listener; HTTP still creates threads per connection.
- No complete coordinated shutdown of both listeners.
- No authentication, TLS, origins enforcement or Internet deployment safeguards.
- Hardcoded loopback ports and a hardcoded browser WebSocket URL.

## Testing and presentation

Automated HTTP and raw WebSocket integration tests exist, and a Chromium end-to-end browser smoke test has been added. These cover representative interactions, not full protocol fuzzing or chess legality. This project can be described as a **Java networking/WebSocket chess prototype**, but **not** as a fully playable chess application.

See [ROADMAP.md](ROADMAP.md) for potential future work. C++ engine integration, a C++ rewrite and an SDL3/WebAssembly 3D chessboard are optional ideas rather than planned cleanup requirements.
