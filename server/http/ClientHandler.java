package server.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.Scanner;

public class ClientHandler implements Runnable{
    private Socket clientSocket;
    final private InputStream in;
    final private OutputStream out;
    final private Scanner s;

    public ClientHandler(Socket socket) throws IOException {
        clientSocket = socket;
        in =  clientSocket.getInputStream();
        out = clientSocket.getOutputStream();
        s = new Scanner(in, "UTF-8");
    }

    public void run() {
        while (true) {
            try {
                System.out.println();
                handleReq(clientSocket);
            } catch (Exception e) {
                System.out.println("Failed to handle request");
            }
        }
    }

    private void handleReq(Socket client) throws IOException, NoSuchAlgorithmException {
        String data = s.useDelimiter("\\r\\n\\r\\n").next();
        Request req = new Request(data);

        if (in.available() > 0) {
            req.setBody(in.readAllBytes());
        }

        // System.out.println(req.uri + " from port " + clientSocket.getPort());

        if ("/".equals(req.uri)) {
            String body = getResource("chessboard.html");
            out.write("HTTP/1.1 200 OK\r\n".getBytes());
            out.write("Connection: Keep-Alive\r\n".getBytes());
            out.write("Content-Type: text/html\r\n".getBytes());
            out.write(("Content-Length: " + body.length() + "\r\n").getBytes());
            out.write("\r\n".getBytes());
            out.write(body.getBytes());
            out.flush();

        } else if (req.uri.endsWith(".ico")) {
            out.write("HTTP/1.1 404 Not Found\r\n".getBytes());
            out.write("\r\n".getBytes());
            out.flush();

        } else {
            String fileName = req.uri.split("/")[1];

            String body = getResource(fileName);
            String type = getType(fileName);
            // System.out.println();
            out.write("HTTP/1.1 200 OK\r\n".getBytes());
            out.write("Connection: Keep-Alive\r\n".getBytes());
            out.write(("Content-Type: " + type + "\r\n").getBytes());
            out.write(("Content-Length: " + body.length() + "\r\n").getBytes());
            out.write("\r\n".getBytes());
            out.write(body.getBytes());
            out.flush();
        }
    }

    private String getResource(String name) throws IOException{
        // System.out.println(name);
        String content;
        String root = "client/";
        Path filePath = Paths.get(root + name);
        // if (Files.notExists(filePath, null)) {
            
        // }
        content = Files.readString(filePath);
        // System.out.println(content);
        return content;
    }

    private String getType(String name) {
        String type = name.split("\\.")[1];

        if ("js".equals(type)) {
            type = "javascript";
        }

        return "text/" + type;
    }
    
}
