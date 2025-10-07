package org.example;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uses tiny stub servers to validate client behavior without the full AggregationServer:
 * - ContentServer retry on 503 with Retry-After
 * - GetClient pretty-prints JSON and shows 200 OK
 */
class ClientStubsIntegrationTests {

    @Test
    void contentServerRetriesOn503RetryAfter_thenSucceeds200() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            Thread stub = new Thread(() -> {
                try {
                    // 1st connection -> 503 + Retry-After
                    try (Socket c1 = ss.accept()) {
                        String headers = readHeaders(c1.getInputStream());
                        readBodyIfAny(c1.getInputStream(), headers);
                        String resp = "HTTP/1.1 503 Service Unavailable\r\n"
                                + "Retry-After: 1\r\n"
                                + "Content-Length: 0\r\n\r\n";
                        c1.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
                        c1.getOutputStream().flush();
                    }
                    // 2nd connection -> 200 OK
                    try (Socket c2 = ss.accept()) {
                        String headers = readHeaders(c2.getInputStream());
                        readBodyIfAny(c2.getInputStream(), headers);
                        String resp = "HTTP/1.1 200 OK\r\n"
                                + "X-Lamport-Clock: 7\r\n"
                                + "Content-Length: 0\r\n\r\n";
                        c2.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
                        c2.getOutputStream().flush();
                    }
                } catch (IOException ignored) {}
            }, "stub-503-then-200");
            stub.setDaemon(true);
            stub.start();

            Path json = Files.createTempFile("cs-", ".json");
            Files.writeString(json, "{\"id\":\"R1\"}", StandardCharsets.UTF_8);

            String output = runMainWithAgent("org.example.client.ContentServer",
                    "localhost:" + port, json.toString());

            assertTrue(output.contains("200 OK") || output.contains("HTTP/1.1 200"),
                    "Expected final 200, got:\n" + output);
            assertTrue(output.contains("[Retry]") || output.toLowerCase(Locale.ROOT).contains("retry"),
                    "Expected retry log present:\n" + output);
        }
    }

    @Test
    void getClientPrettyPrints200Json() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            Thread stub = new Thread(() -> {
                try (Socket c = ss.accept()) {
                    String headers = readHeaders(c.getInputStream());
                    readBodyIfAny(c.getInputStream(), headers);
                    byte[] body = "{\"id\":\"Z1\",\"temp\":23}".getBytes(StandardCharsets.UTF_8);
                    String resp =
                            "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "X-Lamport-Clock: 42\r\n" +
                                    "Content-Length: " + body.length + "\r\n\r\n";
                    c.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
                    c.getOutputStream().write(body);
                    c.getOutputStream().flush();
                } catch (IOException ignored) {}
            }, "stub-200-json");
            stub.setDaemon(true);
            stub.start();

            String out = runMainWithAgent("org.example.client.GetClient",
                    "localhost:" + port, "/weather.json");

            // GetClient prints: "<code> <reason>\n<pretty JSON>"
            assertTrue(out.contains("200 OK"), "Expected 200 OK in output:\n" + out);
            assertTrue(out.contains("\"id\": \"Z1\""),
                    "Expected pretty-printed JSON with string values:\n" + out);
            // map-to-strings behavior: 23 becomes "23"
            assertTrue(out.contains("\"temp\": \"23\""),
                    "Expected temp to be stringified in pretty map:\n" + out);
        }
    }

    // ---- helpers ----

    /** Read request headers until CRLFCRLF and return them as a String. */
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

    /** If Content-Length is present, read and discard that many bytes from the stream. */
    private static void readBodyIfAny(InputStream in, String headers) throws IOException {
        int len = 0;
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                try { len = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {}
                break;
            }
        }
        if (len > 0) {
            in.readNBytes(len); // drain body so client doesn't block
        }
    }

    /**
     * Spawn a child JVM with the JaCoCo agent injected (so code executed there is covered).
     * The agent string comes from pom: surefire/failsafe set -DjacocoAgent=<agent argLine>.
     */
    private static String runMainWithAgent(String mainClass, String... args) throws Exception {
        String javaHome = System.getProperty("java.home");
        String javaExe = javaHome + File.separator + "bin" + File.separator
                + "java" + (isWindows() ? ".exe" : "");
        String classpath = System.getProperty("java.class.path");
        String agent = System.getProperty("jacocoAgent"); // set by surefire/failsafe config

        ProcessBuilder pb = new ProcessBuilder();
        if (agent != null && !agent.isBlank()) {
            pb.command(javaExe, agent, "-cp", classpath, mainClass);
        } else {
            pb.command(javaExe, "-cp", classpath, mainClass);
        }
        for (String a : args) pb.command().add(a);
        pb.redirectErrorStream(true);

        Process p = pb.start();
        String out = readAllWithTimeout(p.getInputStream(), 8000);
        p.waitFor(8, TimeUnit.SECONDS);
        if (p.isAlive()) p.destroy();
        return out;
    }

    private static String readAllWithTimeout(InputStream in, int timeoutMs) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        do {
            while (in.available() > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, in.available()));
                if (n > 0) bos.write(buf, 0, n);
            }
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        } while (System.currentTimeMillis() < end);
        return bos.toString(StandardCharsets.UTF_8);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }
}
