# Jess: roadmap to a presentable, playable prototype

> **Planning document, not an implementation report.** The current code still uses colon-separated WebSocket messages and has no server-side chess validation. See [the proposed protocol](PROTOCOL.md) and [the existing limitations](KNOWN_ISSUES.md).

## Goal and scope

Make the existing project demonstrably functional and reliable **without committing to Java as its long-term architecture**. Keep one local game, two player seats, optional spectators, and a simple browser interface. Avoid rewriting the networking stack or building a 3D renderer until the minimum working game is achieved.

The near-term target is **a rules-enforcing chess server with a clearly specified client protocol**, not a feature-complete online chess platform.

## Priorities at a glance

| Phase | Focus | Definition of done |
| --- | --- | --- |
| **0 — Baseline** | Test harness and reproducible local demo | A repeatable build, a browser smoke test, and coverage for critical message/frame paths |
| **1 — Stabilize transport** | Eliminate serious socket, framing, and connection-lifecycle bugs | No busy loop, safe frame parsing, bounded sessions, no crash on ordinary disconnect |
| **2 — Specify communication** | Switch client and server to a small versioned JSON contract | Invalid messages are rejected; accepted moves and snapshots have explicit schemas |
| **3 — Enforce chess rules** | Make server state authoritative and validate every move | Legal play, turn order, special moves, and terminal positions work and are tested |
| **4 — Presentable MVP** | Reconnect/snapshot behavior, UX, documentation, CI | Two browsers can finish a valid local game; refresh and spectator joins show correct state |
| **5 — Decide future** | Evaluate C++ engine, backend rewrite, and 3D/WASM UI | A deliberate continuation/stop decision based on the working MVP |

Phase 2 and phase 3 can overlap, but **both must be completed before calling Jess a playable chess application**.

## Phase 0: Establish a baseline

**Tasks**
- Add a test framework to Maven (it is not active in the current `pom.xml`) and a basic CI build/test workflow.
- Add smoke tests for `GET /`, required static files, opening a WebSocket connection to `/game`, role assignment, and a simple message exchange.
- Record expected behavior with two browsers and one spectator. Do not treat manually reproduced moves as evidence of legal move validation.
- Keep an explicit distinction between **existing behavior**, **new protocol design**, and **future ideas**.

**Acceptance:** `mvn test` runs actual tests; the README build instructions are reproducible; basic regressions become visible.

## Phase 1: Fix serious transport and concurrency issues

The current prototype cannot be treated as reliable while malformed or ordinary network traffic can trigger crashes or out-of-bounds access.

**Must fix**
- **WebSocket reads:** accumulate TCP bytes; use the actual `read()` count; decode complete frames regardless of TCP packet boundaries; handle partial, batched and fragmented messages.
- **Frame validity:** implement required masking and lengths correctly, bound incoming message sizes, validate opcodes/control frames, implement close handling, and echo ping payloads in pong responses.
- **Connection lifecycle:** replace fixed, monotonically indexed arrays with a bounded registry that removes disconnected clients and reclaims seats cleanly. Prevent concurrent unsynchronized writes to the same socket.
- **Upgrade handling:** validate `GET`, `/game`, `Upgrade`, `Connection`, `Sec-WebSocket-Version`, and `Sec-WebSocket-Key` before treating a client as WebSocket-connected.
- **HTTP service:** stop spinning on `InputStream.available()`; use bounded blocking reads, return sensible 400/404/405 responses, verify static paths stay within the asset directory, and calculate `Content-Length` from response bytes.
- **Shutdown and errors:** close streams/sockets on all exit paths; log errors without leaking zombie handlers.

**Tests**
- Split a valid frame over several reads; combine two frames in one read.
- Short/extended payload lengths, ping/pong, close, invalid mask/opcode, oversized frames.
- Repeated connects/disconnects exceeding the former ten-slot limit.
- Unknown HTTP files, malformed headers, path traversal attempts, and request timeouts.

**Acceptance:** malformed/slow/disconnected clients are handled without unbounded spinning, unhandled exceptions or corruption of another client's message.

**Keep scope narrow:** keep the existing Java listeners if they can be made safe; don't replace everything with Netty or C++ solely for this phase.

## Phase 2: Formalize client/server messages

Adopt [PROTOCOL.md](PROTOCOL.md) as the target **v1 application protocol**. It is a draft until implemented and exercised by tests.

**Tasks**
- Replace ad hoc strings like `move:e2e4:black` with UTF-8 JSON text messages containing `v` and `type`.
- Specify move coordinates (`from`, `to`, optional `promotion`), `requestId`, `gameId`, and monotonic `revision`.
- Establish `session.welcome`, `game.snapshot`, `move.submit`, `move.accepted`, and `move.rejected` messages.
- Send an authoritative board snapshot on join/rejoin. Clients only apply server-accepted moves or snapshots.
- Reject malformed JSON, unknown types/versions, stale revisions, and invalid field values with stable error codes.
- Add serialization/deserialization contract tests and browser integration tests.

**Acceptance:** the current UI communicates exclusively via the documented format; no move appears on clients until accepted by the server; an invalid command does not mutate a board.

**Migration:** this is an early-stage prototype with no external clients to preserve, so client and server can switch formats together. There is no need for permanent legacy-colon compatibility.

## Phase 3: Add authoritative chess state and legal moves

**Recommended design:** put chess rules behind a small Java interface (for example `ChessRules`/`GameStateService`) so the networking and protocol layers know nothing about how moves are evaluated. **For the fastest presentable result, consider a proven Java rules library instead of building a new Java chess engine.** A custom C++ engine remains an optional, later replacement behind the same contract.

**Tasks**
- Maintain one server-owned game position (including side to move, castling rights, en passant, move counters and result).
- Validate piece ownership, turn order, movement, capture paths, king safety, promotion, castling and en passant.
- Apply a move atomically; increment revision; publish only after successful validation.
- Detect checkmate and stalemate; support game reset for local demonstrations.
- Treat draw rules such as threefold repetition and the fifty-move rule as a planned follow-up if the chosen rules implementation doesn't already support them; label incomplete rules clearly.
- Reject spectator moves, out-of-turn moves, invalid coordinates, illegal moves, and moves after the game has ended.

**Tests**
- Start position has twenty legal moves; illegal two-square knight move is rejected.
- Captures and blocked paths; moving into or remaining in check is rejected.
- Castling, including castling through check; en passant; all promotion choices.
- Checkmate (e.g. Fool's Mate), stalemate, turn switching, game-over behavior.
- Two clients submitting conflicting/stale moves cannot corrupt one state.

**Acceptance:** clients cannot make illegal moves even by sending arbitrary frames; the server is the only authority for positions and turns.

## Phase 4: Make the result presentable

**Minimum demo**
1. Start with documented Maven commands.
2. Open two browser windows and assign white/black roles.
3. Play a legal opening while both boards remain synchronized.
4. Attempt an illegal move and observe a clear rejection.
5. Join as a spectator or refresh a page; see the current position from a server snapshot.
6. Finish a short game with checkmate and show the result.
7. Disconnect and connect again without breaking the server.

**Polish only what helps the demo**
- Highlight selection, moves and invalid-move messages; show role, side to move and game result.
- Provide a restart/new-game action for a single local session.
- Show a connection error state instead of silently failing.
- Add an automated integration test for the core happy path and the invalid-move path.
- Keep clear screenshots or a short demo recording for the README.

**Acceptance:** a stranger can clone, run and understand the project; the demo's claims are supported by behavior and tests.

## Explicitly out of scope for the MVP

Public hosting, accounts, matchmaking, ratings, multiple game rooms, persistent history, chat, clocks, tournament features, engine AI, and rich 3D graphics. They can be explored later without blocking a functional prototype.

## Phase 5: Decide what to do with Jess

After reaching the MVP, choose based on what is most valuable to learn or showcase:

| Option | When it makes sense | Main trade-off |
| --- | --- | --- |
| **Keep Java server, build a C++ chess engine as a separate process** | Want serious C++ engine work while minimizing server changes | Extra process management and IPC protocol; UCI is worth evaluating |
| **C++ engine via JNI** | Need direct in-process calls and accept native boundary complexity | Build portability, ownership, crashes, JNI maintenance |
| **Separate C++ engine service** | Want independent deployment or multiple consumers | More network/API complexity than a local hobby MVP needs |
| **Rewrite the server in C++** | Want networking and game state to contribute directly to a C++ portfolio | Repeats working networking functionality; delays gameplay polish |
| **Add a C++/SDL3 WebAssembly 3D board** | Want a graphics/geometry portfolio showcase | Separate rendering/input effort; doesn't solve chess correctness |

**Suggested default:** reach the MVP using the current Java code with minimal chess-domain coupling. Then consider a separate **C++ engine over a defined IPC boundary** and a **C++/SDL3/WASM board as an independent frontend experiment**. Neither should be a prerequisite for the first usable release.

## Stop/go criteria

Once phase 4 passes, Jess can be considered **presentable and maintained** even if no additional features are built. Continue only if a next feature teaches something personally useful: C++ engine search/evaluation, reliable IPC, real-time WebAssembly rendering, or 3D scene/animation work. Avoid expanding the Java codebase merely to make the chess platform larger.