# Jess architecture and protocol

This document describes the **current implementation**, not the eventual design of a finished chess service. For a future versioned JSON message contract, see [the proposed protocol](PROTOCOL.md). For implementation priorities, see [the roadmap](ROADMAP.md).

## 1. System overview

Jess runs two independent TCP listeners inside one Java process:

```text
                     Browser (HTML / CSS / JavaScript)
                                    |
                     +--------------+--------------+
                     |                             |
                  HTTP GET                    WebSocket
                     |                         ws://localhost:
                     |                         4220/game
                     v                             v
           +------------------+        +---------------------+
           | HTTP listener    |        | WebSocket listener  |
           | port 4221        |        | port 4220           |
           +------------------+        +---------------------+
                     |                             |
             ClientHandler                  WebSocketHandler
                     |                             |
               static files               handshake, frame IO,
               from working               role, broadcast
               directory                            |
                                                   v
                                             clients[] array
                                          (shared connections)
```

The application does **not** maintain a server-side chessboard or chess-engine model. Connected browsers hold their displayed positions independently, and move messages drive updates.

### Entry point

`src/main/java/jess/Main.java` declares `jess.Jess.main`. It constructs an `HttpServer(4221)` and a `WebSocketServer(4220)`, then starts each as its own Java thread. Failure to create one listener is caught and logged separately from failure to create the other.

## 2. HTTP subsystem

**Files:** `server/http/HttpServer.java`, `ClientHandler.java`, `Request.java`.

### Connection model

`HttpServer` uses `java.net.ServerSocket` to accept TCP connections. For every accepted socket it creates a new `ClientHandler` running on a dedicated thread. There is no executor or connection-count limit.

`ClientHandler` loops over requests on the accepted connection. It checks `InputStream.available()` and reads a header using a `Scanner` with `\r\n\r\n` as its delimiter. `Request` extracts the method, URI, version and headers into a map.

### Asset serving

The handler maps `/` to `static/chessboard.html`. Other non-`.ico` requests take the first path segment after `/`, read `static/<filename>` relative to the **process working directory**, and return the file contents.

Typical responses:

| Request | Behavior |
| --- | --- |
| `GET /` | `200 OK` with the chessboard HTML |
| `GET /chessboard.js` | `200 OK` with JavaScript |
| `GET /chessboard.css` | `200 OK` with CSS |
| `GET /pieces.css` | `200 OK` with CSS |
| Path ending in `.ico` | `404 Not Found`, without a response body |
| Missing/unreadable resource | Logs or throws an I/O error; does not consistently return an HTTP error response |

The handler writes `Connection: Keep-Alive`, `Content-Type`, and `Content-Length` for successful responses. The parser and response writer implement only a narrow subset of HTTP/1.1 behavior; they are not standards-complete.

**Important current constraints:**
- The parser does not validate supported methods or HTTP versions.
- Header parsing assumes each header contains a `: ` separator.
- The response length is calculated from Java `String.length()` rather than the encoded body byte count.
- The request handler's idle polling loop can use CPU unnecessarily.
- No orderly client shutdown or request timeout mechanism is present.

## 3. WebSocket subsystem

**Files:** `server/websocket/WebSocketServer.java`, `WebSocketHandler.java`, `Frame.java`, `Enums.java`.

### Connection model and roles

`WebSocketServer` accepts TCP connections on port `4220`, creates a `WebSocketHandler` for each client, and starts a dedicated thread for it. It stores handler references and threads in fixed arrays, each with **10 slots**.

The handler parses the incoming HTTP request and, when the path is `/game` and a `Sec-WebSocket-Key` header exists, responds with `101 Switching Protocols`. The accept key is computed as specified by the WebSocket handshake algorithm: SHA-1 of the client key plus the protocol GUID, then Base64.

After this step, role assignment depends on the connection's monotonically increasing ID:

| Connection number | Assigned role |
| --- | --- |
| 1 | `white` |
| 2 | `black` |
| 3 and later | `spectator` |

These roles are **not** associated with authenticated players or a game session. Disconnected connections are not removed from the array, and the connection counter is not reduced when a client leaves.

### Frames

`Frame.java` includes logic to inspect WebSocket frame headers and encode outgoing text frames. It recognizes opcode categories for continuation, text, binary, close, ping and pong. Payload decoding handles masking and the short and extended payload-length fields in a limited way.

`WebSocketHandler` can reply to ping with pong and responds to close frames. This should not be mistaken for complete RFC 6455 compliance. In particular, `recv()` reads into a fixed 1,024-byte buffer once and passes the whole buffer to the frame constructor, without preserving the number of bytes actually received. Fragmentation, incomplete frames, multiple frames per TCP read, and long payloads are not safely handled.

Outgoing text frames are written directly to each handler's socket stream, without explicit synchronization between concurrent broadcasters.

## 4. Browser UI

**Files:** `chessboard.html`, `chessboard.js`, `chessboard.css`, `pieces.css`.

The UI renders an 8×8 HTML table. Starting pieces are represented with `data-piece` attributes on cells. CSS inserts Unicode chess symbols according to those attributes.

`chessboard.js` opens:

```javascript
const gameSocket = new WebSocket("ws://localhost:4220/game");
```

The browser has a `Player` object with a role and a `canMove` flag. The first click selects the origin square **only if it holds a piece matching the player's assigned color**. The second click chooses any destination, and the browser sends the resulting string to the WebSocket server.

When the browser receives a move message, `movePiece` removes the piece attribute from the origin cell and adds it to the destination cell. This updates the visual board, including overwriting an occupied destination, but does not implement chess rules.

### Client-side turn logic

- White begins with `canMove = true`; black and spectators begin with `false`.
- After receiving `move:<squares>:<nextRole>`, a browser sets `canMove` based on whether `<nextRole>` matches its own role.
- The server does **not** track which color should move next, reject out-of-turn messages, or check legal positions.

## 5. Application message protocol

All application-level messages are currently colon-separated **WebSocket text payloads**, not JSON.

| Direction | Example payload | Meaning |
| --- | --- | --- |
| Server → browser | `role:white` | Tell this connection its role |
| Server → browser | `role:black` | Assign the black role |
| Server → browser | `role:spectator` | Assign spectator role |
| Browser → server | `move:e2e4` | Request a move from `e2` to `e4` |
| Server → all | `move:e2e4:black` | Rebroadcast player message and suggest black as next turn |

The appended next-turn field is derived solely from the **sender's assigned role**. For example, messages sent by the white connection are suffixed with `black`, regardless of the actual board or whether white was entitled to move.

There is no separate acknowledgement or rejection message. Message contents are not formally validated.

### Example message flow

```text
Browser A                           Server                         Browser B
   |                                  |                               |
   |--- WebSocket /game ------------->|                               |
   |<-- role:white -------------------|                               |
   |                                  |<---------- WebSocket /game ----|
   |                                  |---------- role:black -------->|
   |                                  |                               |
   |--- move:e2e4 ------------------->|                               |
   |<-- move:e2e4:black --------------|----- move:e2e4:black -------->|
   |       (local DOM update)         |           (local DOM update)  |
```

Broadcasts also include spectators and other connected handlers in the shared array.

## 6. Concurrency and state ownership

- **HTTP listener:** one acceptor thread, plus a new thread for each HTTP client.
- **WebSocket listener:** one acceptor thread, plus a new thread for each WebSocket client.
- **Shared server state:** an array of `WebSocketHandler` objects and a monotonically increasing connection counter.
- **Game state:** stored only implicitly in each browser's DOM; there is no authoritative server state.

The shared handler array is accessed by multiple threads without a consistent synchronization strategy. A client can also write a frame while another handler is broadcasting to it.

## 7. Recommended architecture if development resumes

A next iteration can preserve the educational value of the custom transport while moving chess semantics into testable, transport-independent components:

```text
WebSocket transport
       |
Message parser and validation
       |
Game session / player registry
       |
Chess state + legal move engine
       |
Accepted move event
       |
Broadcast updated game state
```

Suggested responsibilities:

- `GameState`: pieces, side to move, castling rights, en-passant state, and game status.
- `Move` / `MoveParser`: typed coordinates, promotion information, syntactic validation.
- `MoveValidator` or `ChessEngine`: legal-move generation, check, checkmate and draw conditions.
- `GameSession`: authoritative state, player identities, spectators, join/leave behavior.
- `WebSocketSession`: protocol reads/writes, frame buffering, connection lifecycle.
- `MessageCodec`: validated, versioned messages and explicit success/error responses.

None of these suggested chess/domain modules currently exists in the reviewed source.