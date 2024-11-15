import server.http.HttpServer;
import server.websocket.WebSocketServer;

class Server {
    public static void main(String[] args) {

        try {
            HttpServer httpServer = new HttpServer(4221);
            Thread thread1 = new Thread(httpServer);
            thread1.start();
        } catch(Exception e) {
            System.err.println("Could not start http server");
            System.err.println(e);
        }

        try {
            WebSocketServer webSocketServer = new WebSocketServer(4220);
            Thread thread2 = new Thread(webSocketServer);
            thread2.start();
        } catch(Exception e) {
            System.err.println("Could not start websocket server");
            System.err.println(e);
        }
    }
}
