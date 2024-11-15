package server.http;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class HttpServer implements Runnable{
    int port;
    private ServerSocket server;

    public HttpServer(int port) throws IOException{
        this.port = port;
        server = new ServerSocket(port);
        server.setReuseAddress(true);
    }
    
    public void run(){
        
        try {
            while (true) {
                Socket client = server.accept();

                try {
                    ClientHandler clientHandler = new ClientHandler(client);
                    Thread httpClient = new Thread(clientHandler);
                    httpClient.start();
                } catch (IllegalThreadStateException e) {
                    System.out.println("Could not start a new thread");
                } catch (IOException e) {
                    System.out.println("Could not start a new ClientHandler");
                }
            }
        } catch(IOException e) {
            System.out.println("Could not accept a client");
        } finally {
                // server.stop();
        }
        
    }

    public void stop() throws IOException {
        server.close();
    }
}
