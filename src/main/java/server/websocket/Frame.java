package server.websocket;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import server.websocket.Enums.FrameType;

/**
 * Reads and writes individual RFC 6455 frames on a TCP byte stream.
 * Client frames MUST be masked; server frames MUST NOT be masked.
 */
public final class Frame {
    public static final int MAX_PAYLOAD_BYTES = 8192;

    private final FrameType type;
    private final boolean fin;
    private final byte[] payload;

    private Frame(FrameType type, boolean fin, byte[] payload) {
        this.type = type;
        this.fin = fin;
        this.payload = payload;
    }

    public FrameType getType() {
        return type;
    }

    public boolean isFinal() {
        return fin;
    }

    public byte[] getPayload() {
        return payload;
    }

    /**
     * Returns null only when EOF occurs before a new frame begins.
     * readNBytes handles a frame arriving across multiple TCP reads.
     */
    public static Frame read(InputStream input) throws IOException {
        int first = input.read();
        if (first < 0) return null;

        int second = readByte(input);
        if ((first & 0x70) != 0) throw new ProtocolException(1002, "Reserved bits are not supported");

        boolean fin = (first & 0x80) != 0;
        FrameType type = FrameType.parseFrameType(first & 0x0f);
        if (type == null) throw new ProtocolException(1002, "Unsupported frame opcode");

        if ((second & 0x80) == 0) throw new ProtocolException(1002, "Client frames must be masked");

        int flag = second & 0x7f;
        long length = flag;
        if (flag == 126) {
            length = (readByte(input) << 8) | readByte(input);
            if (length < 126) throw new ProtocolException(1002, "Nonminimal payload length");
        } else if (flag == 127) {
            int high = readByte(input);
            if ((high & 0x80) != 0) throw new ProtocolException(1002, "Invalid 64-bit length");
            length = high;
            for (int i = 1; i < 8; i++) length = (length << 8) | readByte(input);
            if (length < 65536) throw new ProtocolException(1002, "Nonminimal payload length");
        }

        boolean control = type == FrameType.CLOSE || type == FrameType.PING || type == FrameType.PONG;
        if (control && (!fin || length > 125))
            throw new ProtocolException(1002, "Invalid control frame");
        if (type == FrameType.CLOSE && length == 1)
            throw new ProtocolException(1002, "Invalid close payload");
        if (length > MAX_PAYLOAD_BYTES)
            throw new ProtocolException(1009, "Frame payload too large");

        byte[] mask = readExactly(input, 4);
        byte[] payload = readExactly(input, (int) length);
        for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
        return new Frame(type, fin, payload);
    }

    /** Encodes a complete unmasked server frame. Fragmentation is handled by the caller on reads. */
    public static void write(OutputStream output, FrameType type, byte[] data) throws IOException {
        boolean control = type == FrameType.CLOSE || type == FrameType.PING || type == FrameType.PONG;
        if (control && data.length > 125) throw new IllegalArgumentException("Control payload exceeds 125 bytes");

        output.write(0x80 | type.getCode());
        if (data.length < 126) {
            output.write(data.length);
        } else if (data.length <= 65535) {
            output.write(126);
            output.write((data.length >>> 8) & 0xff);
            output.write(data.length & 0xff);
        } else {
            output.write(127);
            long n = data.length;
            for (int i = 7; i >= 0; i--) output.write((int) (n >>> (i * 8)) & 0xff);
        }
        output.write(data);
        output.flush();
    }

    private static int readByte(InputStream input) throws IOException {
        int b = input.read();
        if (b < 0) throw new EOFException("Truncated WebSocket frame");
        return b;
    }

    private static byte[] readExactly(InputStream input, int n) throws IOException {
        byte[] bytes = input.readNBytes(n);
        if (bytes.length != n) throw new EOFException("Truncated WebSocket frame");
        return bytes;
    }

    public static final class ProtocolException extends IOException {
        private final int closeCode;

        public ProtocolException(int code, String message) {
            super(message);
            closeCode = code;
        }

        public int getCloseCode() {
            return closeCode;
        }
    }
}
