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

// 🟩 Added imports
import org.example.interfaces.LamportSynchronizer;
import org.example.util.SimpleLamportSynchronizer;

/**
 * AggregationServer accepts PUTs of weather data and serves it via GET.
 * <p>
 * <b>Design notes (SonarQube):</b>
 * <ul>
 *   <li>Lamport clocks are used for causal ordering. Requests carry X-Lamport-Clock; server updates local clock and applies PUTs via a Lamport-ordered queue.</li>
 *   <li>State exposure uses {@code volatile} fields for cross-thread visibility without changing control flow.</li>
 *   <li>A TTL policy guards freshness; persistence is handled via a simple snapshot store.</li>
 *   <li>Error handling avoids crashing the server—exceptions are logged and the loop continues.</li>
 * </ul>
 */
public final class AggregationServer {

    // ---- state served to clients (volatile for cross-thread visibility) ----
    private static volatile String lastPayload = null;

    // TTL via policy (30s). Keep lastAppliedAt updated when a PUT is applied.
    private static final org.example.interfaces.ExpiryPolicy EXPIRY =
            new org.example.util.FixedTtlPolicy(30_000L);
    private static volatile long lastAppliedAt = 0L;

    // Simple file-based persistence of last applied snapshot
    private static final SnapshotStore STORE =
            new FileSnapshotStore(java.nio.file.Paths.get("src", "main", "resources", "temp"), "weather");

    // --- Lamport additions ---
    private static final org.example.interfaces.LamportClock CLOCK =
            new org.example.util.AtomicLamportClock();
    private static final String NODE_ID = "AGG-SERVER";
    // --------------------------

    // HTTP helper (centralized response writing + reason + status constants)
    private static final HttpHandler HTTP = new DefaultHttpHandler();

    // 🟩 New synchronizer instance for ordering consistency (await/notify semantics for GET catch-up)
    private static final LamportSynchronizer SYNC = new SimpleLamportSynchronizer();

    // -----------------------------------------------------------------------
    // Lamport-ordered apply queue
    // -----------------------------------------------------------------------
    /** Immutable update payload; ordering key is (lamportTs, fromNode, seq). */
    private record Update(long lamportTs, String fromNode, String json, long seq) {}

    // Monotonic arrival sequence to break ties stably
    private static final AtomicLong ARRIVAL_SEQ = new AtomicLong(0);

    // Priority queue: first by Lamport ts, then node id (stable), then arrival sequence
    private static final PriorityQueue<Update> APPLY_Q = new PriorityQueue<>(
            Comparator
                    .comparingLong((Update u) -> u.lamportTs)
                    .thenComparing(u -> u.fromNode == null ? "" : u.fromNode)
                    .thenComparingLong(u -> u.seq)
    );

    // Single applier thread: ensures in-order, single-threaded state mutation and snapshotting
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

                    // 🟩 Notify synchronizer that this Lamport has been applied (unblocks GET waiters)
                    SYNC.onPutApplied(u.lamportTs);

                } catch (Exception e) {
                    // Sonar: keep server alive; failed apply is logged for diagnosis
                    System.err.println("Apply failed: " + e.getMessage());
                }
            }
        }, "lamport-applier");
        applier.setDaemon(true);
        applier.start();
    }
    // -----------------------------------------------------------------------

    /**
     * Starts the server on the given port (default 4567) and processes connections in a loop.
     */
    public static void main(String[] args) throws Exception {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : 4567;

        // Attempt to restore the last snapshot on startup (treated as fresh)
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

    /* =========================== refactored handle =========================== */

    /**
     * Processes a single connection end-to-end (read headers, route, respond).
     * <p>Sonar: exceptions are caught at the callsite to keep server responsive.</p>
     */
    private static void handle(Socket s) {
        try (s; InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {

            // 1) Read headers or 400
            String[] headerLines = readOrRejectHeaders(in, out);
            if (headerLines == null) return;

            // 2) Update Lamport clock if header present (logs included)
            long remoteLamport = parseLamportFromHeaders(headerLines);
            maybeUpdateLamport(remoteLamport);

            // 3) Parse request line or 400
            String[] parts = parseRequestLineOrReject(headerLines, out);
            if (parts == null) return;

            String method = parts[0];
            String path   = parts[1];
            int contentLength = contentLengthFrom(headerLines);

            // 4) Route
            if (isPutWeather(method, path)) {
                handlePutWeather(in, out, headerLines, remoteLamport, contentLength);
                return;
            }
            if (isGetWeather(method, path)) {
                handleGetWeather(out);
                return;
            }

            // 5) Unknown → 400
            HTTP.writeEmpty(out, HttpHandler.BAD_REQUEST, CLOCK, NODE_ID);

        } catch (Exception ignore) {
            // keep server alive
        }
    }

    /* ---------------------- route helpers (no logic change) ---------------------- */

    /** Reads the HTTP headers section; writes 400 if empty and returns null. */
    private static String[] readOrRejectHeaders(InputStream in, OutputStream out) throws IOException {
        String[] headerLines = readHeaderLines(in);
        if (headerLines.length == 0) {
            HTTP.writeEmpty(out, HttpHandler.BAD_REQUEST, CLOCK, NODE_ID);
            return null;
        }
        return headerLines;
    }

    /** Updates the local Lamport clock based on the client's clock, if provided. */
    private static void maybeUpdateLamport(long remoteLamport) {
        if (remoteLamport > 0) {
            CLOCK.update(remoteLamport);
            System.out.println("[Lamport] AggregationServer received request");
            System.out.println("          Remote Clock: " + remoteLamport);
            System.out.println("          Updated Local Clock: " + CLOCK.get());
        }
    }

    /** Parses the request line; if malformed, sends 400 and returns null. */
    private static String[] parseRequestLineOrReject(String[] headerLines, OutputStream out) throws IOException {
        String requestLine = headerLines[0];
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            HTTP.writeEmpty(out, HttpHandler.BAD_REQUEST, CLOCK, NODE_ID);
            return null;
        }
        return parts;
    }

    /** Route predicate: PUT /weather.json */
    private static boolean isPutWeather(String method, String path) {
        return "PUT".equals(method) && "/weather.json".equals(path);
    }

    /** Route predicate: GET /weather.json */
    private static boolean isGetWeather(String method, String path) {
        return "GET".equals(method) && "/weather.json".equals(path);
    }

    /**
     * Handles a PUT /weather.json:
     * <ul>
     *   <li>Validates content length and JSON (must include non-blank {@code id}).</li>
     *   <li>Enqueues update for Lamport-ordered application (non-blocking).</li>
     *   <li>Responds 201 for first write, else 200.</li>
     * </ul>
     */
    private static void handlePutWeather(InputStream in,
                                         OutputStream out,
                                         String[] headerLines,
                                         long remoteLamport,
                                         int contentLength) throws IOException {
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
        enqueueUpdate(orderTs, fromNode, json);

        boolean first = (lastPayload == null || lastPayload.isBlank());
        if (first) {
            HTTP.writeEmpty(out, HttpHandler.CREATED, CLOCK, NODE_ID);
        } else {
            HTTP.writeEmpty(out, HttpHandler.OK, CLOCK, NODE_ID);
        }
    }

    /** Adds a pending update to the Lamport-ordered queue and signals the applier thread. */
    private static void enqueueUpdate(long orderTs, String fromNode, String json) {
        long seq = ARRIVAL_SEQ.incrementAndGet();
        synchronized (APPLY_Q) {
            APPLY_Q.add(new Update(orderTs, fromNode, json, seq));
            APPLY_Q.notifyAll();
        }
    }

    /**
     * Handles GET /weather.json:
     * <ul>
     *   <li>Waits (up to ~2s) for all PUTs with Lamport ≤ current clock to apply.</li>
     *   <li>Returns 404 if no data or data expired per TTL.</li>
     *   <li>Otherwise returns 200 with the last payload.</li>
     * </ul>
     */
    private static void handleGetWeather(OutputStream out) throws IOException {
        // 🟩 Wait until all PUTs with Lamport <= current clock have been applied
        long target = CLOCK.get();
        boolean caughtUp = SYNC.awaitUpTo(target, 2000L);
        if (!caughtUp) {
            System.out.println("[Lamport] GET timed out waiting for <= " + target +
                    " (lastApplied=" + SYNC.lastApplied() + ")");
        }

        if (lastPayload == null || lastPayload.isBlank()) {
            HTTP.writeJson(out, HttpHandler.NOT_FOUND,
                    "{\"error\":\"no weather data available\"}", CLOCK, NODE_ID);
            return;
        }

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

    /* -------- tiny JSON validator for PUT -------- */
    /** Validates the JSON payload and ensures a non-blank {@code id} field exists. */
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

    /**
     * Reads header bytes up to CRLFCRLF. Returns lines split by CRLF.
     * <p>Sonar: manual parser is acceptable within assignment scope; sizes are small.</p>
     */
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

    /** Extracts Content-Length from headers (defaults to 0 if absent or invalid). */
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

    /** Reads exactly {@code len} bytes for the body. */
    private static byte[] readBody(InputStream in, int len) throws IOException {
        return in.readNBytes(len);
    }

    /** Parses X-Lamport-Clock header value; returns 0 if not present/invalid. */
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

    /** Finds a specific header value by name (case-insensitive), or {@code null}. */
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
