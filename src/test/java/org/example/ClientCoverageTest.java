package org.example;

import org.example.client.ContentServer;
import org.example.client.GetClient;
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

class ClientCoverageTest {

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
    private static void startAgg(int port) {
        Thread t = new Thread(() -> {
            try { AggregationServer.main(new String[]{ String.valueOf(port) }); }
            catch (Exception ignored) {}
        }, "agg-for-client");
        t.setDaemon(true);
        t.start();
        try { TimeUnit.MILLISECONDS.sleep(400); } catch (InterruptedException ignored) {}
    }

    // ---- tests ----
    @Test
    @DisplayName("1) ContentServer PUT → AggregationServer; GetClient GET (default path) → 200")
    void e2ePutThenGet() throws Exception {
        wipeSnapshots();
        int port = freePort();
        startAgg(port);

        File tmp = File.createTempFile("weather", ".txt");
        try (FileWriter fw = new FileWriter(tmp)) {
            fw.write("id: c-1\n");
            fw.write("city: Hobart\n");
            fw.write("temp: 18\n");
        }

        ContentServer.main(new String[]{"localhost:" + port, tmp.getAbsolutePath()});

        GetClient gc = new GetClient();
        String resp = gc.fetch("localhost:" + port, null); // default path
        assertTrue(resp.startsWith("200 "), "Got:\n" + resp);
        assertTrue(resp.contains("Hobart"), "Body:\n" + resp);
    }

    @Test
    @DisplayName("2) ContentServer retry branch: 503 (Retry-After) then 200")
    void retry503Then200() throws Exception {
        int port = freePort();

        Thread dummy = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(port)) {
                // 1st: 503 with Retry-After: 1
                try (Socket s = ss.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    in.read(new byte[2048]); // drain
                    String resp = "HTTP/1.1 503 Service Unavailable\r\nRetry-After: 1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                    out.write(resp.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                // 2nd: 200
                try (Socket s = ss.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    in.read(new byte[2048]);
                    String resp = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                    out.write(resp.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException ignored) {}
        }, "retry-dummy");
        dummy.setDaemon(true);
        dummy.start();
        TimeUnit.MILLISECONDS.sleep(150);

        File tmp = File.createTempFile("weather", ".json");
        try (FileWriter fw = new FileWriter(tmp)) { fw.write("{\"id\":\"retry-1\"}"); }

        assertDoesNotThrow(() ->
                ContentServer.main(new String[]{"localhost:" + port, tmp.getAbsolutePath()}));
    }

    @Test
    @DisplayName("3) ContentServer.validateArgs (prints usage and returns)")
    void validateArgsPath() throws Exception {
        ContentServer.main(new String[]{}); // should not throw
    }

    @Test
    @DisplayName("4) Private helpers for branch coverage")
    void privateHelpers() throws Exception {
        // buildBody: key:value -> JSON
        Path kv = Files.createTempFile("kv", ".txt");
        Files.writeString(kv, "a: 1\nb: two\n");
        Method buildBody = ContentServer.class.getDeclaredMethod("buildBody", Path.class);
        buildBody.setAccessible(true);
        String js = new String((byte[]) buildBody.invoke(null, kv), StandardCharsets.UTF_8);
        assertTrue(js.contains("\"a\":\"1\"") && js.contains("\"b\":\"two\""));

        // raw JSON passthrough
        Path raw = Files.createTempFile("raw", ".json");
        Files.writeString(raw, "{\"x\":true}");
        assertEquals("{\"x\":true}", new String((byte[]) buildBody.invoke(null, raw), StandardCharsets.UTF_8));

        // empty file
        Path empty = Files.createTempFile("empty", ".txt");
        Files.write(empty, new byte[0]);
        assertEquals(0, ((byte[]) buildBody.invoke(null, empty)).length);

        // path & header helpers
        Method ensure = ContentServer.class.getDeclaredMethod("ensureLeadingSlash", String.class);
        ensure.setAccessible(true);
        assertEquals("/x", ensure.invoke(null, "x"));

        Method firstLine = ContentServer.class.getDeclaredMethod("firstLine", String.class);
        firstLine.setAccessible(true);
        Method bodyOf = ContentServer.class.getDeclaredMethod("bodyOf", String.class);
        bodyOf.setAccessible(true);
        String http = "HTTP/1.1 200 OK\r\nA: b\r\n\r\nBODY";
        assertEquals("HTTP/1.1 200 OK", firstLine.invoke(null, http));
        assertEquals("BODY", bodyOf.invoke(null, http));

        Method status = ContentServer.class.getDeclaredMethod("statusCodeOf", String.class);
        status.setAccessible(true);
        assertEquals(200, status.invoke(null, "HTTP/1.1 200 OK"));

        Method headerValue = ContentServer.class.getDeclaredMethod("headerValue", String.class, String.class);
        headerValue.setAccessible(true);
        assertEquals("b", headerValue.invoke(null, http, "A"));

        Method parseHost = ContentServer.class.getDeclaredMethod("parseHost", String.class);
        parseHost.setAccessible(true);
        assertEquals("h", parseHost.invoke(null, "h:123/p"));

        Method parsePort = ContentServer.class.getDeclaredMethod("parsePort", String.class, int.class);
        parsePort.setAccessible(true);
        assertEquals(123, parsePort.invoke(null, "h:123/p", 7));
        assertEquals(7, parsePort.invoke(null, "h/p", 7));

        Method parsePath = ContentServer.class.getDeclaredMethod("parsePath", String.class, String.class);
        parsePath.setAccessible(true);
        assertEquals("/p", parsePath.invoke(null, "h:1/p", "/d"));
        assertEquals("/d", parsePath.invoke(null, "h:1", "/d"));
    }
}
