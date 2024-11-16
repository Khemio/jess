package server.websocket;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;


public class WebSocketServer implements Runnable {
    int port;
    ServerSocket server;
    private int clientNum = 0;
    private int capacity = 10;

    public WebSocketHandler[] clients = new WebSocketHandler[capacity];
    public Thread[] tarray = new Thread[capacity];

    public WebSocketServer(int port) throws IOException {
        this.port = port;
        server = new ServerSocket(port);
    }

    public void run(){
        // ServerSocket 
        System.out.println("Server has started on 127.0.0.1:" + port + ".\r\nWaiting for a connection…");

        try {
            while (true) {
                    Socket client = server.accept(); //TODO: Monitor capacity
                    // System.out.println("A client connected.");

                    try {
                        clientNum++;
                        clients[clientNum - 1] = new WebSocketHandler(client, clientNum, clients);
                        Thread websocket = new Thread(clients[clientNum - 1]);
                        websocket.setName("websocket-" + clientNum);
                        websocket.start();
                        tarray[clientNum - 1] = websocket;
                    } catch (IllegalThreadStateException e) {
                        clientNum--;
                        System.out.println("Could not start a new thread for WebSocketHandler");
                    } catch (IOException e) {
                        clientNum--;
                        System.out.println("Could not start a new WebSocketHandler");
                    }

                }
            } catch(IOException e) {
                System.out.println("Could not accept a websocket client");
            } finally {
                    // server.stop();
            }
        }
        
    public void stop() {
        for (int i = 0; i < capacity; i++) {
            if (clients[i] != null) clients[i].stop();
            if (tarray[i] != null) {
                try {
                    tarray[i].join();
                }catch (InterruptedException e) {}
            }
        }
    }
}