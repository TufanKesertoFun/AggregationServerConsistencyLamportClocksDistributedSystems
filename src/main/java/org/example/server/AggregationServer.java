package org.example.server;

import org.example.persistance.FileSnapshotStore;
import org.example.interfaces.SnapshotStore;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.example.interfaces.HttpHandler;
import org.example.http.DefaultHttpHandler;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class AggregationServer {

    // ---- state served to clients (volatile for cross-thread visibility) ----
    private static volatile String lastPayload = null;

    // TTL via policy (30s). Keep lastAppliedAt updated when a PUT is applied.
    private static final org.example.interfaces.ExpiryPolicy EXPIRY =
            new org.example.util.FixedTtlPolicy(30_000L);
    private static volatile long lastAppliedAt = 0L;

    private static final SnapshotStore STORE =
            new FileSnapshotStore(java.nio.file.Paths.get("src", "main", "resources", "temp"), "weather");

    // --- Lamport additions ---
    private static final org.example.interfaces.LamportClock CLOCK =
            new org.example.util.AtomicLamportClock();
    private static final String NODE_ID = "AGG-SERVER";
    // --------------------------

    // HTTP helper (centralized response writing + reason + status constants)
    private static final HttpHandler HTTP = new DefaultHttpHandler();

    // -----------------------------------------------------------------------
    // Lamport-ordered apply queue
    // -----------------------------------------------------------------------
    private record Update(long lamportTs, String fromNode, String json, long seq) {}

    private static final AtomicLong ARRIVAL_SEQ = new AtomicLong(0);

    private static final PriorityQueue<Update> APPLY_Q = new PriorityQueue<>(
            Comparator
                    .comparingLong((Update u) -> u.lamportTs)
                    .thenComparing(u -> u.fromNode == null ? "" : u.fromNode)
                    .thenComparingLong(u -> u.seq)
    );

    static {
        Thread applier = new Thread(() -> {
            while (true) {
                Update u;
                synchronized (APPLY_Q) {
                    while (APPLY_Q.isEmpty()) {
                        try { APPLY_Q.wait(); } catch (InterruptedException ignored) {}
                    }
                    u = APPLY_Q.poll();
                }
                try {
                    // Apply in order (single-threaded here)
                    lastPayload = u.json;
                    STORE.save(lastPayload);
                    lastAppliedAt = System.currentTimeMillis(); // record apply time for TTL

                    System.out.println("[Lamport-Apply] ts=" + u.lamportTs +
                            " fromNode=" + (u.fromNode == null ? "?" : u.fromNode) +
                            " seq=" + u.seq + " -> applied & snapshotted");
                } catch (Exception e) {
                    System.err.println("Apply failed: " + e.getMessage());
                }
            }
        }, "lamport-applier");
        applier.setDaemon(true);
        applier.start();
    }
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : 4567;

        String snap = STORE.load();
        if (snap != null && !snap.isBlank()) {
            lastPayload = snap;
            lastAppliedAt = System.currentTimeMillis(); // treat restored snapshot as fresh now
            System.out.println("Restored snapshot from resources/temp/latest.json");
        }

        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("Listening on " + port);
            while (true) {
                Socket s = ss.accept();
                new Thread(() -> handle(s)).start();
            }
        }
    }

    private static void handle(Socket s) {
        try (s; InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {

            String[] headerLines = readHeaderLines(in);
            if (headerLines.length == 0) {
                HTTP.writeEmpty(out, HttpHandler.BAD_REQUEST, CLOCK, NODE_ID);
                return;
            }

            // --- Lamport: update from requester (if present) ---
            long remoteLamport = parseLamportFromHeaders(headerLines);
            if (remoteLamport > 0) {
                CLOCK.update(remoteLamport);

                System.out.println("[Lamport] AggregationServer received request");
                System.out.println("          Remote Clock: " + remoteLamport);
                System.out.println("          Updated Local Clock: " + CLOCK.get());
            }
            // ---------------------------------------------------

            String requestLine = headerLines[0];
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                HTTP.writeEmpty(out, HttpHandler.BAD_REQUEST, CLOCK, NODE_ID);
                return;
            }

            String method = parts[0];
            String path = parts[1];
            int contentLength = contentLengthFrom(headerLines);

            if ("PUT".equals(method) && "/weather.json".equals(path)) {
                if (contentLength <= 0) {
                    HTTP.writeEmpty(out, HttpHandler.NO_CONTENT, CLOCK, NODE_ID);
                    return;
                }

                byte[] body = readBody(in, contentLength);
                String json = new String(body, StandardCharsets.UTF_8);

                try {
                    validateJsonOrThrow(json);
                } catch (Exception e) {
                    HTTP.writeJson(out, HttpHandler.INTERNAL_SERVER_ERROR,
                            "{\"error\":\"invalid JSON or missing id\"}", CLOCK, NODE_ID);
                    return;
                }

                String fromNode = parseHeaderValue(headerLines, "X-Lamport-Node");
                long orderTs = (remoteLamport > 0) ? remoteLamport : CLOCK.get();
                long seq = ARRIVAL_SEQ.incrementAndGet();
                synchronized (APPLY_Q) {
                    APPLY_Q.add(new Update(orderTs, fromNode, json, seq));
                    APPLY_Q.notifyAll();
                }

                boolean first = (lastPayload == null || lastPayload.isBlank());
                if (first) {
                    HTTP.writeEmpty(out, HttpHandler.CREATED, CLOCK, NODE_ID);
                } else {
                    HTTP.writeEmpty(out, HttpHandler.OK, CLOCK, NODE_ID);
                }
                return;
            }

            if ("GET".equals(method) && "/weather.json".equals(path)) {
                if (lastPayload == null || lastPayload.isBlank()) {
                    HTTP.writeJson(out, HttpHandler.NOT_FOUND,
                            "{\"error\":\"no weather data available\"}", CLOCK, NODE_ID);
                } else {
                    long now = System.currentTimeMillis();
                    if (EXPIRY.isExpired(lastAppliedAt, now)) {
                        long age = now - lastAppliedAt;
                        System.out.println("[TTL] Data expired: ageMs=" + age + " > " + EXPIRY.ttlMs());
                        HTTP.writeJson(out, HttpHandler.NOT_FOUND,
                                "{\"error\":\"data expired\"}", CLOCK, NODE_ID);
                    } else {
                        HTTP.writeJson(out, HttpHandler.OK, lastPayload, CLOCK, NODE_ID);
                    }
                }
                return;
            }

            HTTP.writeEmpty(out, HttpHandler.BAD_REQUEST, CLOCK, NODE_ID);

        } catch (Exception ignore) {
            // keep server alive
        }
    }

    /* -------- tiny JSON validator for PUT -------- */
    private static void validateJsonOrThrow(String json) throws Exception {
        var element = JsonParser.parseString(json);
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }
        JsonObject obj = element.getAsJsonObject();
        if (!obj.has("id")) {
            throw new IllegalArgumentException("missing id");
        }
        if (obj.get("id").isJsonNull()) {
            throw new IllegalArgumentException("null id");
        }
        String id = obj.get("id").getAsString();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("blank id");
        }
    }

    /* -------------------- helpers -------------------- */
    private static String[] readHeaderLines(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        int b;

        while ((b = in.read()) != -1) {
            buf.write(b);
            if (state == 0 && b == '\r') {
                state = 1;
            } else if (state == 1 && b == '\n') {
                state = 2;
            } else if (state == 2 && b == '\r') {
                state = 3;
            } else if (state == 3 && b == '\n') {
                break;
            } else {
                state = 0;
            }
        }

        String headersStr = buf.toString(StandardCharsets.UTF_8);
        if (headersStr.isEmpty()) {
            return new String[0];
        } else {
            return headersStr.split("\r\n");
        }
    }

    private static int contentLengthFrom(String[] headerLines) {
        for (String line : headerLines) {
            if (line == null) continue;
            String lower = line.toLowerCase();
            if (lower.startsWith("content-length:")) {
                try {
                    return Integer.parseInt(line.substring(15).trim());
                } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    private static byte[] readBody(InputStream in, int len) throws IOException {
        return in.readNBytes(len);
    }

    private static long parseLamportFromHeaders(String[] headerLines) {
        for (String line : headerLines) {
            if (line == null) continue;
            int i = line.indexOf(':');
            if (i > 0) {
                String name = line.substring(0, i).trim();
                if ("X-Lamport-Clock".equalsIgnoreCase(name)) {
                    try {
                        return Long.parseLong(line.substring(i + 1).trim());
                    } catch (Exception ignored) {}
                }
            }
        }
        return 0L;
    }

    private static String parseHeaderValue(String[] headerLines, String wantedName) {
        for (String line : headerLines) {
            if (line == null) continue;
            int i = line.indexOf(':');
            if (i > 0) {
                String name = line.substring(0, i).trim();
                if (wantedName.equalsIgnoreCase(name)) {
                    return line.substring(i + 1).trim();
                }
            }
        }
        return null;
    }
}
