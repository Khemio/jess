package server.websocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Scanner;

import server.http.Request;
import server.websocket.Enums.FrameType;
import server.websocket.Enums.Role;;


public class WebSocketHandler implements Runnable {
    final private Socket socket;
    final private InputStream in;
    final private OutputStream out;
    final private Scanner s;
    final private int clientId;
    private WebSocketHandler[] clients;

    private boolean running = true;

    private Role role;


    public WebSocketHandler(Socket client, int id, WebSocketHandler[] clients) throws IOException {
        socket = client;
        in =  socket.getInputStream();
        out = socket.getOutputStream();
        s = new Scanner(in, "UTF-8");
        clientId = id;
        this.clients = clients;
    }

    public void run() {
        try {
            handleReq();
        }
        catch(IOException e) {
            System.out.println("handleReq() IOException");
        } catch(NoSuchAlgorithmException e) {
            System.out.println("handleReq() NoSuchAlgorithmException");
        }
    }

    private void handleReq() throws IOException, NoSuchAlgorithmException {
        String data = s.useDelimiter("\\r\\n\\r\\n").next();
        Request req = new Request(data);
        if (in.available() > 0) {
            req.setBody(in.readAllBytes());
        }

        // System.out.println("clientId: " + clientId);
        try {
            if ("/game".equals(req.uri)) {
                // System.out.println("request head: " + req.showHead());
                String socketKey = req.get("Sec-WebSocket-Key");
                // System.out.println(socketKey);
                if (socketKey != null) {
                    handshake(socketKey);
                }

                // Setup client
                if (clientId < 3) {
                    role = clientId == 1 ? Role.WHITE : Role.BLACK;
                    // send("role:" + role);
                } else {
                    role = Role.SPECTATOR;
                }
                send("role:" + role.getValue());

                while (running) {
                    String message = recv();

                    if (!message.isEmpty() && running) {
                        handleMessage(message);
                    }
                }
            }
        } finally {
            s.close();
        }
    }

    public void handleMessage(String msg) {
        System.out.println("recieved message: " + msg);
        String next;
        switch (role) {
            case WHITE:
                next = "black";
                break;
            case BLACK:
                next = "white";
                break;
            case null, default:
                return;
        }
        brodcast(msg + ":" + next);
    }

    public void stop() {
        // Maybe send closing frame?
        running = false;
        // s.close();
        // socket.close();
    }

    private void handshake(String clientKey) throws NoSuchAlgorithmException, UnsupportedEncodingException {
        byte[] response = ("HTTP/1.1 101 Switching Protocols\r\n"
            + "Connection: Upgrade\r\n"
            + "Upgrade: websocket\r\n"
            + "Sec-WebSocket-Accept: "
            + generateAcceptKey(clientKey)
            + "\r\n\r\n").getBytes("UTF-8");

        try {
            out.write(response, 0, response.length); 
        } catch (IOException e) {
            System.out.println("clientId:" + clientId + " Could not complete hanshake: write failed");
            System.out.println(e);
            running = false;
            s.close();

        }
    }

    private String generateAcceptKey(String clientKey) throws NoSuchAlgorithmException, UnsupportedEncodingException {
        byte[] keyBytes = (clientKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8");
        byte[] keySha1 = MessageDigest.getInstance("SHA-1").digest(keyBytes);
        var e = Base64.getEncoder();

        return e.encodeToString(keySha1);
    }

    public void send(String msg) {
        Frame outFrame = new Frame(msg, FrameType.TEXT, false);
        try {
            out.write(outFrame.getBytes()); 
        } catch (IOException e) {
            System.out.println("Could not send message: write failed");
            System.out.println(e);
        }
    }

    public void brodcast(String msg) {
        for (WebSocketHandler client: clients) {
            if (client != null) client.send(msg);
        }
    }

    private String recv() {
        int size = 1024;
        byte[] encoded = new byte[size];
    
        try {
            in.read(encoded);
        } catch (IOException e) {
            System.out.println("Could not read from websocket");
            System.err.println(e);
            running = false;
            s.close();
        }
        Frame inFrame = new Frame(encoded);

        switch (inFrame.getType()) {
            case CLOSE:
                // TODO: Handle close code decoding
                // int code = ByteBuffer.allocate(8).put(bMsg).getInt();
                // System.out.println("clientId: " + clientId);
                // System.out.println("close code: %02X %02X".formatted(bMsg[0], bMsg[1]));
                handleCloseFrame();
                break;

            case PING:
                sendControlFrame(FrameType.PONG);
                break;

            case TEXT, BINARY, CONTINUATION, PONG:
                break;

            case null, default:
                System.err.println("Wrong optcode");
                sendCloseFrame(1002);
                break;
        }
        
        return inFrame.showMessage();
    }

    private void handleCloseFrame() {
        sendCloseFrame(1000);
    }

    private void sendCloseFrame(int code) {
        byte[] bCode = ByteBuffer.allocate(4).putInt(code).array();
        byte[] resp = new byte[4];

        resp[0] = (byte) (128 | FrameType.CLOSE.getCode());
        resp[1] = 2;
        resp[2] = bCode[2];
        resp[3] = bCode[3];

        try {
            out.write(resp);
            running = false;
            s.close();
        } catch (IOException e) {
            System.out.println("something went wrong");
        }
    }

    private void sendCloseFrame(int code, String reason) {
        byte[] bCode = ByteBuffer.allocate(4).putInt(code).array();
        byte[] payload = ("  " + reason).getBytes(); 
        payload[0] = bCode[2];
        payload[1] = bCode[3];
        byte[] resp = new byte[payload.length + 2];

        resp[0] = (byte) (128 | FrameType.CLOSE.getCode());
        resp[1] = (byte) payload.length;

        for (int i = 0; i < payload.length; i++) {
            resp[i + 2] = payload[i];
        }

        try {
            out.write(resp);
            running = false;
            s.close();
        } catch (IOException e) {
            System.out.println("something went wrong");
        }
    }

    private void sendControlFrame(FrameType type) {
        byte[] resp = new byte[2];

        resp[0] = (byte) (128 | type.getCode());
        resp[1] = 0;

        try {
            out.write(resp);
        } catch (IOException e) {
            System.out.println("something went wrong");
        }
    }

    private void sendControlFrame(FrameType type, String msg) {
        byte[] bMsg = msg.getBytes();
        byte[] resp = new byte[bMsg.length + 2];

        resp[0] = (byte) (128 | type.getCode());
        resp[1] = (byte) bMsg.length;

        for (int i = 0; i < bMsg.length; i++) {
            resp[i + 2] = bMsg[i];
        }

        try {
            out.write(resp);
        } catch (IOException e) {
            System.out.println("something went wrong");
        }
    }

}
