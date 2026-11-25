package server.websocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import server.websocket.Enums.FrameType;
import server.websocket.Enums.Role;

/**
 * One WebSocket connection. The chess application still uses the legacy
 * text protocol (role:<color>, move:<from><to>:<next>).
 */
public final class WebSocketHandler implements Runnable {
    private static final int MAX_HEADER_BYTES = 8192;
    private final Socket socket;
    private final WebSocketServer server;
    private final Object writeLock = new Object();
    private InputStream in;
    private OutputStream out;
    private Role role;

    public WebSocketHandler(Socket socket, WebSocketServer server) {
        this.socket = socket;
        this.server = server;
    }

    @Override
    public void run() {
        try (socket) {
            in = socket.getInputStream();
            out = socket.getOutputStream();
            if (!handshake()) return;

            role = server.register(this);
            send("role:" + role.getValue());

            ByteArrayOutputStream fragments = null;
            while (!socket.isClosed()) {
                Frame frame;
                try {
                    frame = Frame.read(in);
                } catch (Frame.ProtocolException ex) {
                    closeWithCode(ex.getCloseCode());
                    return;
                }

                if (frame == null) return;
                switch (frame.getType()) {
                    case PING -> sendControl(FrameType.PONG, frame.getPayload());
                    case PONG -> { /* Reply to our own pings would be handled here. */ }
                    case CLOSE -> {
                        sendControl(FrameType.CLOSE, frame.getPayload()); // Echo close frame, then exit.
                        return;
                    }
                    case BINARY -> {
                        closeWithCode(1003); // This application supports text messages only.
                        return;
                    }
                    case TEXT -> {
                        if (fragments != null) {
                            closeWithCode(1002); // New data frame before previous fragmented message ended.
                            return;
                        }
                        if (frame.isFinal()) {
                            if (!handleText(frame.getPayload())) return;
                        } else {
                            fragments = new ByteArrayOutputStream();
                            fragments.writeBytes(frame.getPayload());
                        }
                    }
                    case CONTINUATION -> {
                        if (fragments == null) {
                            closeWithCode(1002);
                            return;
                        }
                        if (fragments.size() + frame.getPayload().length > Frame.MAX_PAYLOAD_BYTES) {
                            closeWithCode(1009);
                            return;
                        }
                        fragments.writeBytes(frame.getPayload());
                        if (frame.isFinal()) {
                            if (!handleText(fragments.toByteArray())) return;
                            fragments = null;
                        }
                    }
                }
            }
        } catch (IOException e) {
            // EOF and disconnect are expected. The finally block reclaims the seat and capacity.
            if (!socket.isClosed()) System.err.println("WebSocket client ended: " + e.getMessage());
        } finally {
            server.disconnect(this);
        }
    }

    private boolean handleText(byte[] payload) throws IOException {
        final String message;
        try {
            message = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException e) {
            closeWithCode(1007); // Invalid UTF-8 in a text message.
            return false;
        }

        // The legacy application format remains unchanged; legal chess moves are NOT checked.
        if (role == Role.SPECTATOR || !message.matches("move:[a-h][1-8][a-h][1-8]")) return true;
        String next = role == Role.WHITE ? "black" : "white";
        server.broadcast(message + ":" + next);
        return true;
    }

    public void send(String message) {
        try {
            sendControl(FrameType.TEXT, message.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            stop();
        }
    }

    private void sendControl(FrameType type, byte[] payload) throws IOException {
        synchronized (writeLock) {
            Frame.write(out, type, payload); // One writer at a time per socket.
        }
    }

    private void closeWithCode(int code) throws IOException {
        sendControl(FrameType.CLOSE, new byte[]{ (byte) (code >>> 8), (byte) code });
    }

    public void stop() {
        try {
            socket.close(); // Interrupts blocking reads and triggers cleanup in finally.
        } catch (IOException e) {
            // Already closed.
        }
    }

    private boolean handshake() throws IOException {
        socket.setSoTimeout(10_000);
        String header = readHeader(in);
        if (header == null) return false;

        String[] lines = header.split("\r\n");
        if (lines.length == 0 || !"GET /game HTTP/1.1".equals(lines[0]))
            return rejectHandshake();

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) return rejectHandshake();
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT),
                    lines[i].substring(colon + 1).trim());
        }

        String key = headers.get("sec-websocket-key");
        if (!"websocket".equalsIgnoreCase(headers.get("upgrade"))
                || !hasToken(headers.get("connection"), "upgrade")
                || !"13".equals(headers.get("sec-websocket-version"))
                || !validKey(key)) return rejectHandshake();

        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-1").digest(
                    (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                            .getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by the WebSocket protocol", e);
        }
        String accept = Base64.getEncoder().encodeToString(digest);
        out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        socket.setSoTimeout(120_000);
        return true;
    }

    private static boolean hasToken(String value, String token) {
        if (value == null) return false;
        for (String part : value.split(",")) {
            if (token.equalsIgnoreCase(part.trim())) return true;
        }
        return false;
    }

    private static boolean validKey(String key) {
        if (key == null) return false;
        try {
            return Base64.getDecoder().decode(key).length == 16;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean rejectHandshake() throws IOException {
        byte[] message = "Bad WebSocket upgrade request\n".getBytes(StandardCharsets.US_ASCII);
        out.write(("HTTP/1.1 400 Bad Request\r\n"
                + "Connection: close\r\n"
                + "Content-Length: " + message.length + "\r\n"
                + "Content-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(message);
        out.flush();
        return false;
    }

    /** Stop exactly at the header terminator to avoid consuming the first WebSocket frame. */
    private static String readHeader(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        int matched = 0;
        while (bytes.size() < MAX_HEADER_BYTES) {
            int next = input.read();
            if (next < 0) {
                if (bytes.size() == 0) return null;
                throw new IOException("Incomplete WebSocket handshake");
            }
            bytes.write(next);
            matched = next == terminator[matched] ? matched + 1 : (next == '\r' ? 1 : 0);
            if (matched == terminator.length) return bytes.toString(StandardCharsets.ISO_8859_1);
        }
        throw new IOException("Oversized WebSocket handshake");
    }
}
