package org.example;

import org.example.client.ContentServer;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ContentServerInJvmIT {

    @Test
    void put_withTextFile_buildsJsonAndSendsLamportHeaders_then200() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            Thread stub = new Thread(() -> {
                try (Socket c = ss.accept()) {
                    // read headers
                    String headers = readHeaders(c.getInputStream());
                    // must contain Lamport headers and correct method/path
                    assertTrue(headers.startsWith("PUT /weather.json HTTP/1.1"), headers);
                    assertTrue(headers.contains("X-Lamport-Node:"), headers);
                    assertTrue(headers.contains("X-Lamport-Clock:"), headers);
                    // read body
                    int len = contentLength(headers);
                    byte[] body = c.getInputStream().readNBytes(Math.max(0, len));
                    String bodyStr = new String(body, StandardCharsets.UTF_8);
                    // text file should be converted to JSON {id:..., name:..., state:...}
                    assertTrue(bodyStr.startsWith("{") && bodyStr.contains("\"id\""), bodyStr);

                    // respond 200
                    c.getOutputStream().write((
                            "HTTP/1.1 200 OK\r\n" +
                                    "X-Lamport-Clock: 10\r\n" +
                                    "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    c.getOutputStream().flush();
                } catch (IOException ignored) {}
            });
            stub.setDaemon(true);
            stub.start();

            Path txt = Files.createTempFile("weather", ".txt");
            Files.writeString(txt, "id:IDS60901A\nname:Adelaide\nstate:SA", StandardCharsets.UTF_8);

            // Call main() directly -> runs in THIS JVM (covered)
            ContentServer.main(new String[]{"localhost:" + port, txt.toString()});
        }
    }

    @Test
    void put_retriesOn503RetryAfter_then200() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            Thread stub = new Thread(() -> {
                try {
                    // 1st connection -> 503 with Retry-After
                    try (Socket c1 = ss.accept()) {
                        readHeadersAndBody(c1.getInputStream());
                        c1.getOutputStream().write((
                                "HTTP/1.1 503 Service Unavailable\r\n" +
                                        "Retry-After: 1\r\n" +
                                        "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        c1.getOutputStream().flush();
                    }
                    // 2nd connection -> 200
                    try (Socket c2 = ss.accept()) {
                        readHeadersAndBody(c2.getInputStream());
                        c2.getOutputStream().write((
                                "HTTP/1.1 200 OK\r\n" +
                                        "X-Lamport-Clock: 11\r\n" +
                                        "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        c2.getOutputStream().flush();
                    }
                } catch (IOException ignored) {}
            });
            stub.setDaemon(true);
            stub.start();

            Path json = Files.createTempFile("weather", ".json");
            Files.writeString(json, "{\"id\":\"R1\",\"name\":\"Retry\",\"state\":\"SA\"}", StandardCharsets.UTF_8);

            ContentServer.main(new String[]{"localhost:" + port, json.toString()});
        }
    }

    // ---- helpers ----
    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0, b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') break;
            else state = 0;
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
    private static void readHeadersAndBody(InputStream in) throws IOException {
        String h = readHeaders(in);
        int len = contentLength(h);
        if (len > 0) in.readNBytes(len);
    }
    private static int contentLength(String headers) {
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                try { return Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {}
            }
        }
        return 0;
    }
}
