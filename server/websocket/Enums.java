package server.websocket;

public class Enums {
    static enum FrameType {
        CONTINUATION(0x0, "continuation"),      // 0b00000000; 0x0; // continuation
        TEXT(0x1, "text"),                      // 0b00000001; 0x1; // text
        BINARY(0x2, "binary"),                  // 0b00000010; 0x2; // binary
        CLOSE(0x8, "close"),                    // 0b00001000; 0x8; // close
        PING(0x9, "ping"),                      // 0b00001001; 0x9; // ping
        PONG(0xA, "pong");                      // 0b00001010; 0xA; // pong
    
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
