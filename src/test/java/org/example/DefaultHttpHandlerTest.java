package org.example;

import org.example.http.DefaultHttpHandler;
import org.example.interfaces.HttpHandler;
import org.example.interfaces.LamportClock;
import org.example.util.AtomicLamportClock;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultHttpHandlerTest {

    @Test
    void buildRequestIncludesHeadersAndLength() {
        DefaultHttpHandler h = new DefaultHttpHandler();
        String req = h.buildRequest("PUT", "/p", "localhost", 4567,
                Map.of("X-Test","1", "User-Agent", "X"), 42);

        assertTrue(req.startsWith("PUT /p HTTP/1.1\r\n"));
        assertTrue(req.contains("Host: localhost:4567\r\n"));
        assertTrue(req.contains("X-Test: 1\r\n"));
        assertTrue(req.contains("User-Agent: X\r\n"));
        assertTrue(req.endsWith("Content-Length: 42\r\n\r\n"));
    }

    @Test
    void sendWritesHeadersAndBody() throws IOException {
        DefaultHttpHandler h = new DefaultHttpHandler();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String headers = "POST / HTTP/1.1\r\nContent-Length: 5\r\n\r\n";
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);

        h.send(out, headers, body);
        String sent = out.toString(StandardCharsets.UTF_8);
        assertEquals(headers + "hello", sent);
    }

    @Test
    void readRawResponseReadsAll() throws IOException {
        DefaultHttpHandler h = new DefaultHttpHandler();
        String resp = "HTTP/1.1 200 OK\r\nX: 1\r\n\r\nbody";
        InputStream in = new ByteArrayInputStream(resp.getBytes(StandardCharsets.UTF_8));
        String got = h.readRawResponse(in);
        assertEquals(resp, got);
    }

    @Test
    void reasonMapsKnownCodes() {
        DefaultHttpHandler h = new DefaultHttpHandler();
        assertEquals("OK", h.reason(HttpHandler.OK));
        assertEquals("Created", h.reason(HttpHandler.CREATED));
        assertEquals("No Content", h.reason(HttpHandler.NO_CONTENT));
        assertEquals("Bad Request", h.reason(HttpHandler.BAD_REQUEST));
        assertEquals("Not Found", h.reason(HttpHandler.NOT_FOUND));
        assertEquals("Internal Server Error", h.reason(HttpHandler.INTERNAL_SERVER_ERROR));
        assertEquals("Unknown", h.reason(418));
    }

    @Test
    void writeEmptyAndJsonIncludeLamportHeadersAndTick() throws IOException {
        DefaultHttpHandler h = new DefaultHttpHandler();
        LamportClock clock = new AtomicLamportClock();

        ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        h.writeEmpty(out1, HttpHandler.OK, clock, "NODE-A");
        String r1 = out1.toString(StandardCharsets.UTF_8);
        assertTrue(r1.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(r1.contains("X-Lamport-Node: NODE-A\r\n"));
        assertTrue(r1.contains("X-Lamport-Clock: 1\r\n"));
        assertTrue(r1.endsWith("Content-Length: 0\r\nConnection: close\r\n\r\n"));

        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        h.writeJson(out2, HttpHandler.CREATED, "{\"id\":\"X\"}", clock, "NODE-A");
        String r2 = out2.toString(StandardCharsets.UTF_8);
        assertTrue(r2.startsWith("HTTP/1.1 201 Created\r\n"));
        assertTrue(r2.contains("Content-Type: application/json\r\n"));
        assertTrue(r2.contains("X-Lamport-Node: NODE-A\r\n"));
        assertTrue(r2.contains("X-Lamport-Clock: 2\r\n")); // ticked again
        assertTrue(r2.contains("\r\n\r\n{\"id\":\"X\"}"));
    }
}
