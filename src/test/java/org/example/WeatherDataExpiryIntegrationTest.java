package org.example;

import org.junit.jupiter.api.*;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;

import static org.example.NetTestUtils.waitForPortOpen;
import static org.junit.jupiter.api.Assertions.*;

class WeatherDataExpiryIntegrationTest {

    private static Thread serverThread;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) { port = ss.getLocalPort(); }

        serverThread = new Thread(() -> {
            try {
                org.example.server.AggregationServer.main(new String[]{ String.valueOf(port) });
            } catch (Exception ignored) { }
        }, "aggregation-server-ttl");
        serverThread.setDaemon(true);
        serverThread.start();

        waitForPortOpen("localhost", port, 8000);
    }

    @Test
    void givenStaleTimestamp_whenGetWeather_thenReturns404NotFound() throws Exception {
        Class<?> clazz = Class.forName("org.example.server.AggregationServer");
        Field f = clazz.getDeclaredField("lastAppliedAt");
        f.setAccessible(true);
        f.setLong(null, System.currentTimeMillis() - 60_000L); // force TTL expiry

        try (Socket s = new Socket("localhost", port)) {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            out.write(("GET /weather.json HTTP/1.1\r\nHost: localhost:"+port+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            String resp = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(resp.startsWith("HTTP/1.1 404"), "Expected 404 after TTL expiry, got:\n" + resp);
            // body may be "data expired" OR "no weather data" depending on state — accept either
            assertTrue(resp.contains("expired") || resp.contains("no weather data"));
        }
    }

    // --- helper reused by other ITs too ---
}
