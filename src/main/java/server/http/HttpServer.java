package server.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

public final class HttpServer implements Runnable {
    private final ServerSocket server;

    public HttpServer(int port) throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", port));
    }

    @Override
    public void run() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Thread thread = new Thread(new ClientHandler(client), "http-client-" + client.getPort());
                thread.start();
            } catch (SocketException e) {
                if (!server.isClosed()) {
                    System.err.println("HTTP accept failed: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                System.err.println("HTTP accept failed: " + e.getMessage());
            }
        }
    }

    public void stop() throws IOException {
        server.close();
    }
}
