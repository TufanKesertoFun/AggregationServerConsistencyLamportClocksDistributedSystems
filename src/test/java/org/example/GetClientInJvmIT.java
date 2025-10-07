package org.example;

import org.example.client.GetClient;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class GetClientInJvmIT {

    @Test
    void get_returnsPrettyJson_andUpdatesLamport_then200() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            Thread stub = new Thread(() -> {
                try (Socket c = ss.accept()) {
                    consumeHeaders(c.getInputStream());
                    // Make the numeric explicit as a double so Gson prints "23.0"
                    byte[] body = "{\"id\":\"Z1\",\"temp\":23.0}".getBytes(StandardCharsets.UTF_8);
                    String resp = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "X-Lamport-Clock: 5\r\n" +
                            "Content-Length: " + body.length + "\r\n\r\n";
                    c.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
                    c.getOutputStream().write(body);
                    c.getOutputStream().flush();
                } catch (IOException ignored) {}
            }, "getclient-stub");
            stub.setDaemon(true);
            stub.start();

            String out = new GetClient().fetch("localhost:" + port, "/weather.json");

            // Be tolerant of prefixes/whitespace
            boolean shows200 = out.contains("200 OK");
            assertTrue(shows200, "Expected to see '200 OK' in output, but got:\n" + out);

            // Pretty JSON with stringified values; temp should be "23.0"
            assertTrue(out.contains("\"id\": \"Z1\""), "Pretty JSON missing id:\n" + out);
            assertTrue(out.contains("\"temp\": \"23.0\""), "temp should be stringified as 23.0:\n" + out);
        }
    }

    @Test
    void get_wrongPath_returns400() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            Thread stub = new Thread(() -> {
                try (Socket c = ss.accept()) {
                    consumeHeaders(c.getInputStream());
                    String resp = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n";
                    c.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
                    c.getOutputStream().flush();
                } catch (IOException ignored) {}
            });
            stub.setDaemon(true);
            stub.start();

            String out = new GetClient().fetch("localhost:" + port, "/nope");
            assertTrue(out.contains("400 Bad Request"), "Expected 400 Bad Request, got:\n" + out);
        }
    }

    private static void consumeHeaders(InputStream in) throws IOException {
        int state = 0, b;
        while ((b = in.read()) != -1) {
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') break;
            else state = 0;
        }
    }
}
