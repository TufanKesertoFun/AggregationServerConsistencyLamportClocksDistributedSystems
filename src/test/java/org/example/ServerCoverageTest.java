package org.example;

import org.example.server.AggregationServer;
import org.junit.jupiter.api.*;

import java.io.*;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.DisplayName.class)
class ServerCoverageTest {

    // ---- utilities ----
    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) { return ss.getLocalPort(); }
    }

    private static void wipeSnapshots() {
        Path dir = Paths.get("src", "main", "resources", "temp");
        try {
            if (Files.exists(dir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                    for (Path p : ds) {
                        String f = p.getFileName().toString();
                        if (f.equals("latest.json") || (f.startsWith("weather-") && f.endsWith(".json"))) {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        }
                    }
                }
            }
        } catch (IOException ignored) {}
    }

    private static void startServer(int port) {
        Thread t = new Thread(() -> {
            try { AggregationServer.main(new String[]{ String.valueOf(port) }); }
            catch (Exception ignored) {}
        }, "agg-server-test");
        t.setDaemon(true);
        t.start();
        try { TimeUnit.MILLISECONDS.sleep(400); } catch (InterruptedException ignored) {}
    }

    private static String sendRaw(int port, String headers, byte[] body) throws IOException {
        try (Socket s = new Socket("localhost", port)) {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            out.write(headers.getBytes(StandardCharsets.UTF_8));
            if (body != null && body.length > 0) out.write(body);
            out.flush();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---- tests ----
    @Test
    @DisplayName("1) Empty headers → 400")
    void garbageHeaders400() throws Exception {
        wipeSnapshots();
        int port = freePort();
        startServer(port);

        String resp = sendRaw(port, "\r\n\r\n", new byte[0]);
        assertTrue(resp.contains(" 400 "), "Expected 400; got:\n" + resp);
    }



    @Test
    @DisplayName("3) PUT invalid JSON (no id) → 500")
    void putInvalidJson500() throws Exception {
        wipeSnapshots();
        int port = freePort();
        startServer(port);

        byte[] body = "{}".getBytes(StandardCharsets.UTF_8); // missing "id"
        String req = "PUT /weather.json HTTP/1.1\r\n" +
                "Host: localhost:"+port+"\r\n" +
                "X-Lamport-Clock: 7\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n\r\n";
        String resp = sendRaw(port, req, body);
        assertTrue(resp.contains(" 500 "), "Expected 500; got:\n" + resp);
        assertTrue(resp.contains("invalid JSON"), "Expected error body; got:\n" + resp);
    }


    @Test
    @DisplayName("5) Helper branches via reflection")
    void helpersByReflection() throws Exception {
        String[] lines = {
                "PUT /weather.json HTTP/1.1",
                "Host: x",
                "Content-Length: 7",
                "X-Lamport-Clock: 42"
        };
        Method mCL = AggregationServer.class.getDeclaredMethod("contentLengthFrom", String[].class);
        mCL.setAccessible(true);
        assertEquals(7, (int) mCL.invoke(null, (Object) lines));

        Method mLC = AggregationServer.class.getDeclaredMethod("parseLamportFromHeaders", String[].class);
        mLC.setAccessible(true);
        assertEquals(42L, (long) mLC.invoke(null, (Object) lines));

        Method mPH = AggregationServer.class.getDeclaredMethod("parseHeaderValue", String[].class, String.class);
        mPH.setAccessible(true);
        assertEquals("42", mPH.invoke(null, (Object) lines, "X-Lamport-Clock"));
    }
}
