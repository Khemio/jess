package server.websocket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import server.websocket.Enums.Role;

/**
 * A small, local, single-game WebSocket server.
 * Capacity is reserved before spawning a connection thread and released at disconnect.
 */
public final class WebSocketServer implements Runnable {
    private static final int MAX_CONNECTIONS = 10;

    private final ServerSocket server;
    private final Semaphore slots = new Semaphore(MAX_CONNECTIONS);
    private final Set<WebSocketHandler> sessions = ConcurrentHashMap.newKeySet();
    private final Set<WebSocketHandler> active = new HashSet<>();
    private WebSocketHandler white;
    private WebSocketHandler black;

    public WebSocketServer(int port) throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", port));
    }

    @Override
    public void run() {
        System.out.println("WebSocket listening on 127.0.0.1:" + server.getLocalPort());
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                if (!slots.tryAcquire()) {
                    client.close(); // Bounded number of threads, including pending handshakes.
                    continue;
                }

                try {
                    WebSocketHandler handler = new WebSocketHandler(client, this);
                    sessions.add(handler);
                    new Thread(handler, "websocket-" + client.getPort()).start();
                } catch (RuntimeException e) {
                    slots.release();
                    client.close();
                    System.err.println("Could not start WebSocket client: " + e.getMessage());
                }
            } catch (SocketException e) {
                if (!server.isClosed()) System.err.println("WebSocket accept failed: " + e.getMessage());
                break;
            } catch (IOException e) {
                System.err.println("WebSocket accept failed: " + e.getMessage());
            }
        }
    }

    /** Called only after a successful HTTP upgrade. */
    public synchronized Role register(WebSocketHandler handler) {
        active.add(handler);
        if (white == null) {
            white = handler;
            return Role.WHITE;
        }
        if (black == null) {
            black = handler;
            return Role.BLACK;
        }
        return Role.SPECTATOR;
    }

    /** Called exactly once from each handler's finally block. */
    public synchronized void disconnect(WebSocketHandler handler) {
        active.remove(handler);
        if (white == handler) white = null;
        if (black == handler) black = null;
        if (sessions.remove(handler)) slots.release();
    }

    public void broadcast(String message) {
        List<WebSocketHandler> recipients;
        synchronized (this) {
            recipients = new ArrayList<>(active);
        }
        for (WebSocketHandler recipient : recipients) recipient.send(message);
    }

    public void stop() throws IOException {
        server.close();
        for (WebSocketHandler session : sessions) session.stop();
    }
}
