package server.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves one HTTP request per connection. Closing the connection avoids
 * advertising keep-alive until a proper persistent HTTP parser exists.
 */
public final class ClientHandler implements Runnable {
    private static final int MAX_HEADER_BYTES = 8192;
    private static final int READ_TIMEOUT_MS = 10_000;

    private final Socket socket;

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try (socket) {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String header;
            try {
                header = readHeader(in);
            } catch (SocketTimeoutException e) {
                send(out, 408, "Request Timeout", "text/plain; charset=utf-8", "Request timed out\n".getBytes(StandardCharsets.UTF_8));
                return;
            } catch (IOException e) {
                send(out, 400, "Bad Request", "text/plain; charset=utf-8", "Malformed request\n".getBytes(StandardCharsets.UTF_8));
                return;
            }

            if (header == null) {
                return; // Client disconnected before sending a request.
            }

            String[] lines = header.split("\r\n");
            String[] first = lines[0].split(" ", -1);
            if (first.length != 3 || !first[2].matches("HTTP/1\\.[01]") || !first[1].startsWith("/")) {
                sendText(out, 400, "Bad Request", "Malformed request line\n");
                return;
            }
            if (!"GET".equals(first[0])) {
                sendText(out, 405, "Method Not Allowed", "Only GET is supported\n");
                return;
            }

            // An explicit allowlist prevents path traversal and accidental file exposure.
            String asset = switch (first[1]) {
                case "/" -> "chessboard.html";
                case "/chessboard.js" -> "chessboard.js";
                case "/chessboard.css" -> "chessboard.css";
                case "/pieces.css" -> "pieces.css";
                default -> null;
            };
            if (asset == null) {
                sendText(out, 404, "Not Found", "Not found\n");
                return;
            }

            Path path = Path.of("static", asset);
            if (!Files.isRegularFile(path)) {
                sendText(out, 404, "Not Found", "Not found\n");
                return;
            }

            byte[] body = Files.readAllBytes(path);
            String contentType = asset.endsWith(".html") ? "text/html; charset=utf-8"
                    : asset.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : "text/css; charset=utf-8";
            send(out, 200, "OK", contentType, body);
        } catch (IOException e) {
            System.err.println("HTTP client connection failed: " + e.getMessage());
        }
    }

    private static void sendText(OutputStream out, int code, String reason, String body) throws IOException {
        send(out, code, reason, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, int code, String reason, String contentType, byte[] body) throws IOException {
        byte[] head = ("HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Connection: close\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        out.write(head);
        out.write(body);
        out.flush();
    }

    /** Returns null for an idle peer that cleanly closes without sending bytes. */
    private static String readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int match = 0;
        byte[] end = { '\r', '\n', '\r', '\n' };
        while (bytes.size() < MAX_HEADER_BYTES) {
            int b = in.read();
            if (b < 0) {
                if (bytes.size() == 0) return null;
                throw new IOException("Incomplete request header");
            }
            bytes.write(b);
            match = b == end[match] ? match + 1 : (b == '\r' ? 1 : 0);
            if (match == end.length) {
                return bytes.toString(StandardCharsets.ISO_8859_1);
            }
        }
        throw new IOException("Request header exceeds limit");
    }
}
