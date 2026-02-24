package server.websocket;

import java.nio.ByteBuffer;
import java.util.Arrays;

import server.websocket.Enums.FrameType;

public class Frame {
    private final int fin;
    private final int rsv1;
    private final int rsv2;
    private final int rsv3;
    private final int optCode;
    private final boolean hasMask;
    private final int lenFlag;
    private final int length;
    private final byte[] mask;
    private final FrameType type;
    private final byte[] payload;
 

    public Frame(byte[] message) {
        byte firstByte = message[0];

        // TODO: handle fragmentation
        fin = firstByte & 0b10000000;
        rsv1 = firstByte & 0b01000000;
        rsv2 = firstByte & 0b00100000;
        rsv3 = firstByte & 0b00010000;
        optCode = firstByte & 0b00001111;

        type = FrameType.parseFrameType(optCode);


        hasMask = (message[1] & 0b10000000) != 0;
        lenFlag = message[1] & 0b01111111; 
        int maskStart = 2;
        int len = lenFlag;

        if (lenFlag == 126) {
            int lenEnd = 3;
            len = 0;

            for (int i = 2; i <= lenEnd; i++) {
                len += Byte.toUnsignedInt(message[i]);
                if (i != lenEnd) {
                    len <<= 8;
                }
            }

            maskStart = 4;
        } else if (lenFlag == 127) {
            System.out.println("length more than 64K, this is untested");
            int lenEnd = 9;
            len = 0;

            for (int i = 2; i <= lenEnd; i++) {
                len += Byte.toUnsignedInt(message[i]);
                if (i != lenEnd) {
                    len <<= 8;
                }
            }
            maskStart = 10;
        }
        length = len;

        if (length != 0) {
            payload = new byte[length];
            int maskEnd = maskStart + (hasMask ? 4 : 0);
            mask = Arrays.copyOfRange(message, maskStart, maskEnd);
    
            for (int i = 0; i < length; i++) {
                payload[i] = (byte) (message[i + maskEnd] ^ mask[i & 0x3]);
            }
        } else {
            payload = "".getBytes();
            mask = null;
        }
    }

    public Frame(String message, FrameType type, boolean hasMask) {
        payload = message.getBytes();
        length = payload.length;
        this.hasMask = hasMask;
        mask = null;

        fin = 0b10000000; // is full message, 0b00000000 for a fragment
        rsv1 = 0b00000000; // reserved value, 0b01000000 for true
        rsv2 = 0b00000000; // reserved value, 0b00100000 for true
        rsv3 = 0b00000000; // reserved value, 0b00010000 for true

        // TODO: Test opt codes
        // optCode = FrameType.TEXT.getCode();
        this.type = type;
        optCode = type.getCode();

        
        // hasMask = 0b00000000; // 0b10000000 if mask is present
        hasMask = false;
        // int lenByte;
        
        if (length < 126) {
            lenFlag = length;
        } else if (length < 65536) {
            lenFlag = 126;
        } else {
            lenFlag = 127;
        }
        
    }

    public byte[] getBytes() {
        byte firstByte = (byte) (fin | rsv1 | rsv2 | rsv3 | optCode);
        byte secondByte = (byte) ((hasMask ? 0x80 : 0x00) | lenFlag);

        int frameSize;
        int payloadStart;

        if (lenFlag < 126) {
            payloadStart = 2;
            frameSize = length + 2;
        } else if (lenFlag == 126) {
            payloadStart = 4;
            frameSize = length + 4;
        } else {
            payloadStart = 10;
            frameSize = length + 10;
        }

        byte[] frameBytes = new byte[frameSize];

        frameBytes[0] = firstByte;
        frameBytes[1] = secondByte;

        if (lenFlag == 126) {
            byte[] byteLen = ByteBuffer.allocate(4).putInt(length).array();
            frameBytes[2] = byteLen[2];
            frameBytes[3] = byteLen[3];

        } else if (lenFlag == 127) {
            System.out.println("Not tested, might be problems with byte order");
            byte[] byteLen = ByteBuffer.allocate(8).putInt(length).array();
            
            for (int i = 0; i < byteLen.length; i++) {
                frameBytes[i + 2] = byteLen[i];
            }
        }

        for (int i = payloadStart; i < frameSize; i++) {
            frameBytes[i] = payload[i - payloadStart];
        }

        return frameBytes;
    }

    public FrameType getType() {
        return type;
    }

    public byte[] getPayload() {
        return payload;
    }

    public String showMessage() {
        return new String(payload);
    }
}
