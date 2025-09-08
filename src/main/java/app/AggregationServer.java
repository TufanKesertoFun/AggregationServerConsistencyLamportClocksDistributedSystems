package app;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class AggregationServer {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 4567;

        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("[Server] listening on port " + port);

            while (true) {
                try (Socket client = server.accept();
                     OutputStream out = client.getOutputStream()) {

                    System.out.println("[Server] client connected: " + client.getInetAddress());

                    String body = "Hello, world!";
                    String response =
                            "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: text/plain\r\n" +
                                    "Connection: close\r\n" +
                                    "Content-Length: " + body.getBytes().length + "\r\n" +
                                    "\r\n" +
                                    body;

                    out.write(response.getBytes());
                    out.flush();
                } catch (Exception e) {
                    System.err.println("[Server] error handling client: " + e.getMessage());
                }
            }
        }
    }
}
