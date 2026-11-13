# Jess WebSocket application protocol — proposed v1

> **Draft / NOT IMPLEMENTED.** The running code still uses colon-delimited strings (`role:white`, `move:e2e4`, `move:e2e4:black`). This document specifies a target to implement during [roadmap phase 2](ROADMAP.md), not behavior available today.

## Scope

This is the **application message format** carried over the existing WebSocket connection at `/game`. WebSocket framing, HTTP upgrade rules, ping/pong and close handling remain governed by RFC 6455 and must be fixed independently.

For the initial MVP: one active game identified by `default`, two player roles (`white`, `black`), and any number of spectators within configured connection limits. The server owns the chess position and has the final decision on all moves.

## Base rules

- All application messages are **UTF-8 JSON WebSocket text messages**. Reject binary application messages.
- All messages require an integer `v` (currently `1`) and a string `type`.
- Every game-related message includes `gameId`. For the MVP this is `"default"`.
- Move submissions include a client-generated `requestId` (string) to correlate replies.
- The server maintains an integer `revision`, initially `0`, increasing by one for each accepted move or reset. It never trusts a revision or board position supplied by a client.
- A `move` consists of `from` and `to` in lowercase algebraic coordinates (`a1` through `h8`), and optional `promotion` from `q`, `r`, `b`, `n`.
- The server rejects unknown message types, unsupported versions, wrong field types, malformed coordinates, and unreasonably large messages. An initial application-message limit of **8 KiB** is proposed.
- Roles are assigned by the server. Spectators cannot submit moves.
- The server sends a **complete state snapshot on connection**; browsers do not assume the initial HTML board is the current game.
- Accepted moves are the only updates that advance the authoritative position; clients must not optimistically mutate it.

Fields shown below are deliberately small. Additional metadata must not silently alter these semantics.

## Messages

### 1. `session.welcome` — server → one client

Sent after a successful WebSocket handshake and role assignment.

```json
{
  "v": 1,
  "type": "session.welcome",
  "gameId": "default",
  "role": "white",
  "revision": 0,
  "state": {
    "fen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
    "turn": "white",
    "status": "active"
  }
}
```

`role` is `"white"`, `"black"` or `"spectator"`. `fen` is a standard Forsyth–Edwards Notation string; `turn` must agree with it. `status` begins as `"active"`.

### 2. `move.submit` — client → server

```json
{
  "v": 1,
  "type": "move.submit",
  "gameId": "default",
  "requestId": "m-001",
  "expectedRevision": 0,
  "move": {
    "from": "e2",
    "to": "e4"
  }
}
```

For a promotion include `"promotion": "q"` (or `r`, `b`, `n`). A client must not send an updated board or a claimed next turn. The server checks `expectedRevision`, player's role, whose turn it is, and full chess legality before accepting.

### 3. `move.accepted` — server → all clients

```json
{
  "v": 1,
  "type": "move.accepted",
  "gameId": "default",
  "requestId": "m-001",
  "revision": 1,
  "move": {
    "from": "e2",
    "to": "e4"
  },
  "state": {
    "fen": "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
    "turn": "black",
    "status": "active"
  }
}
```

Broadcast only **after** the server has committed the legal move. `requestId` identifies the originating submission; recipients that did not send that request can ignore this field. The full state is included initially to simplify synchronization and debugging; it can be optimized later if justified.

Proposed game statuses: `active`, `checkmate`, `stalemate`, `draw`, `resigned`. The rules implementation must define exactly when each occurs; do not advertise unsupported outcomes as implemented.

### 4. `move.rejected` — server → submitting client

```json
{
  "v": 1,
  "type": "move.rejected",
  "gameId": "default",
  "requestId": "m-002",
  "revision": 1,
  "error": {
    "code": "illegal_move",
    "message": "The requested move is not legal in the current position."
  }
}
```

Candidate stable codes: `bad_message`, `unsupported_version`, `not_your_turn`, `spectator_cannot_move`, `illegal_move`, `stale_revision`, `game_over`. The browser must display errors to the submitting user and leave its board unchanged. Human-readable `message` is diagnostic; clients should branch on `code`.

### 5. `game.sync` — client → server

```json
{
  "v": 1,
  "type": "game.sync",
  "gameId": "default"
}
```

Request an authoritative snapshot after the client detects a missed revision or other inconsistency. The server also proactively sends a state with `session.welcome` on initial connection.

### 6. `game.snapshot` — server → one client

```json
{
  "v": 1,
  "type": "game.snapshot",
  "gameId": "default",
  "revision": 1,
  "state": {
    "fen": "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
    "turn": "black",
    "status": "active"
  }
}
```

The browser replaces its current displayed position with the snapshot, rather than replaying untrusted local guesses.

## State machine and ordering

1. Client opens WebSocket at `/game`.
2. Server validates the upgrade, associates a role/session, and sends `session.welcome` with the current revision and position.
3. Active player sends `move.submit` with `expectedRevision`.
4. Server validates message schema, role, turn, revision, and move legality.
5. If rejected: send `move.rejected` **only** to the submitter; do not mutate state or increment revision.
6. If accepted: atomically update game state and increment revision; broadcast `move.accepted` to players and spectators.
7. Client ignores duplicate accepted revisions, and sends `game.sync` if a revision gap is observed.
8. When a player disconnects, server retains the game state. Role/reconnection policy must be defined before allowing a replacement to move.

Use one ordering/serialization point per game so concurrent move submissions cannot both apply to the same revision. In-process Java implementation may use a synchronized game-session method or a single-threaded game event loop.

## Compatibility / migration

The v1 JSON format intentionally **replaces** the existing `:`-delimited messages. Maintain a consistent v1 schema between frontend and backend; add tests from stored JSON fixtures.

A migration does not need a legacy adapter unless external clients actually depend on it. Update the UI and server together, with the current implementation and proposed contract documented separately.

## Tests required before claiming v1 support

- Valid and invalid JSON, missing fields, unknown versions/types, wrong field types, invalid coordinates.
- Submit from a spectator and from the wrong side; neither changes the state.
- A legal move increments revision exactly once and appears identically on both player browsers.
- An illegal move produces `move.rejected`, not `move.accepted`.
- Two simultaneous submissions with the same expected revision cannot both succeed.
- New spectator / refreshed browser receives the current FEN; an observed revision gap triggers `game.sync`.
- All application messages stay within configured size limits.

## Security boundary

The v1 contract is for a **local educational application**. It is not an authentication or internet deployment design. Do not expose this service publicly without authorization, TLS, origin policy, connection limits, robust parsing, and abuse controls.