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

        try {
            if ("/game".equals(req.uri)) {
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

                    if (!message.isEmpty()) {
                        handleMessage(message);
                    }
                }
            }
        } finally {
            s.close();
        }
    }

    public void handleMessage(String msg) {
        System.out.println(msg);
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
            System.out.println(Thread.currentThread());
            System.out.println("Could not complete hanshake: write failed");
            running = false;
            s.close();

        }
    }

    public void send(String msg) {
        try {
            out.write(encode(msg)); 
        } catch (IOException e) {
            System.out.println("Could not complete hanshake: write failed");
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
            System.out.println("Could not read from socket");
        }

        String message = decode(encoded);
        // System.out.println(message);

        return message;
    }

    private String generateAcceptKey(String clientKey) throws NoSuchAlgorithmException, UnsupportedEncodingException {
        byte[] keyBytes = (clientKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8");
        byte[] keySha1 = MessageDigest.getInstance("SHA-1").digest(keyBytes);
        var e = Base64.getEncoder();

        return e.encodeToString(keySha1);
    }

    private byte[] encode(String msg) {
        byte[] bMsg = msg.getBytes();

        int fin = 0b10000000; // is full message, 0b00000000 for a fragment
        int rsv1 = 0b00000000; // reserved value, 0b01000000 for true
        int rsv2 = 0b00000000; // reserved value, 0b00100000 for true
        int rsv3 = 0b00000000; // reserved value, 0b00010000 for true

        // TODO: Test opt codes
        int optCode = FrameType.TEXT.getCode();

        byte firstByte = (byte) (fin | rsv1 | rsv2 | rsv3 | optCode);
        
        int hasMask = 0b00000000; // 0b10000000 if mask is present
        int lenByte;

        if (bMsg.length < 126) {
            lenByte = bMsg.length;
        } else if (bMsg.length < 65536) {
            lenByte = 126;
        } else {
            lenByte = 127;
        }

        byte secondByte = (byte) (hasMask | lenByte);


        int outLen;
        int msgStart;

        if (lenByte < 126) {
            msgStart = 2;
            outLen = bMsg.length + 2;
        } else if (lenByte == 126) {
            msgStart = 4;
            outLen = bMsg.length + 4;
        } else {
            msgStart = 10;
            outLen = bMsg.length + 10;
        }

        byte[] payload = new byte[outLen];

        payload[0] = firstByte;
        payload[1] = secondByte;

        if (lenByte == 126) {
            byte[] byteLen = ByteBuffer.allocate(4).putInt(bMsg.length).array();
            payload[2] = byteLen[2];
            payload[3] = byteLen[3];

        } else if (lenByte == 127) {
            System.out.println("Not tested, might be problems with byte order");
            byte[] byteLen = ByteBuffer.allocate(8).putInt(bMsg.length).array();
            
            for (int i = 0; i < byteLen.length; i++) {
                payload[i + 2] = byteLen[i];
            }
        }

        for (int i = msgStart; i < outLen; i++) {
            payload[i] = bMsg[i - msgStart];
        }

        return payload;
    }

    private String decode(byte[] encoded) {
        byte firstByte = encoded[0];

        // TODO: handle fragmentation
        int fin = firstByte & 0b10000000;
        int rsv1 = firstByte & 0b01000000;
        int rsv2 = firstByte & 0b00100000;
        int rsv3 = firstByte & 0b00010000;
        int optCode = firstByte & 0b00001111;

        // optCode parsing
        FrameType type = FrameType.parseFrameType(optCode);
        // System.out.println(type.getValue());

        boolean hasMask = (encoded[1] & 0b10000000) != 0;
        int len = encoded[1] & 0b01111111; 
        int maskStart = 2;

        if (len == 126) {
            int lenEnd = 3;
            len = 0;

            for (int i = 2; i <= lenEnd; i++) {
                len += Byte.toUnsignedInt(encoded[i]);
                if (i != lenEnd) {
                    len <<= 8;
                }
            }

            maskStart = 4;
        } else if (len == 127) {
            System.out.println("length more than 64K, this is untested");
            int lenEnd = 9;
            len = 0;

            for (int i = 2; i <= lenEnd; i++) {
                len += Byte.toUnsignedInt(encoded[i]);
                if (i != lenEnd) {
                    len <<= 8;
                }
            }
            maskStart = 10;
        }

        byte[] bMsg = "".getBytes();
        if (len != 0) {
            bMsg = new byte[len];
            byte[] mask = new byte[4];
            int maskEnd = maskStart + (hasMask ? 4 : 0);
            mask = Arrays.copyOfRange(encoded, maskStart, maskEnd);
    
            for (int i = 0; i < len; i++) {
                bMsg[i] = (byte) (encoded[i + maskEnd] ^ mask[i & 0x3]);
            }
        }

        switch (type) {
            case CLOSE:
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
        
        return new String(bMsg);
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

    static enum FrameType {
        CONTINUATION(0b00000000, "continuation"),      // 0b00000000; 0x0; // continuation
        TEXT(0b00000001, "text"),                      // 0b00000001; 0x1; // text
        BINARY(0b00000010, "binary"),                  // 0b00000010; 0x2; // binary
        CLOSE(0b00001000, "close"),                    // 0b00001000; 0x8; // close
        PING(0b00001001, "ping"),                      // 0b00001001; 0x9; // ping
        PONG(0b00001010, "pong");                      // 0b00001010; 0xA; // pong
    
        final private int code;
        final private String value;
    
        FrameType(int code, String value) {
            this.code = code;
            this.value = value;
        }
    
        public static FrameType parseFrameType(int code) {
            for (FrameType type : values()) {
                if (code == type.code) {
                    return type;
                }
            }
    
            return null;
        }
    
        public int getCode() {
            return this.code;
        }
    
        public String getValue() {
            return this.value;
        }
    }

    static enum Role {
        WHITE("white"),
        BLACK("black"),
        SPECTATOR("spectator");

        final private String value;

        Role(String value) {
            this.value = value;
        }

        String getValue() {
            return value;
        }
    }
}
