package server.http;

import java.util.HashMap;

public class Request {
    final public String method;
    final public String uri;
    final public String version;

    private HashMap<String, String> headers = new HashMap<String, String>();
    // public String body;
    public byte[] body;

    public Request(String head) {
        String[] lines = head.split("\r\n");
        
        String[] tokens = lines[0].split(" ");
        method = tokens[0];
        uri = tokens[1];
        version = tokens[2];

        for (int i = 1; i < lines.length; i++) {
            tokens = lines[i].split(": ");
            headers.put(tokens[0], tokens[1]);
        }
    }

    public String get(String key) {
        return headers.get(key);
    }

    public String showHead() {
        return "%s %s %s".formatted(method, uri, version);
    }

    public void setBody(byte[] body) {
        this.body = body;
    }
}
