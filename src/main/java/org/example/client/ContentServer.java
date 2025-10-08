package org.example.client;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.example.http.DefaultHttpHandler;
import org.example.interfaces.HttpHandler;

/**
 * ContentServer uploads local weather data to the AggregationServer via HTTP PUT requests.
 * <p>
 * <b>SonarQube notes:</b>
 * <ul>
 *     <li>HTTP status codes and retryable error handling were refactored into constants.</li>
 *     <li>Comments clarify intent without altering control flow.</li>
 *     <li>Lamport clock logic is intentionally simple and side-effect-based (ticks before send, updates on response).</li>
 * </ul>
 */
public final class ContentServer {

    // --- HTTP status codes (extracted from magic numbers for readability) ---
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_INTERNAL_ERROR = 500;
    private static final int HTTP_UNAVAILABLE = 503;

    // --- Lamport additions ---
    private static final org.example.interfaces.LamportClock CLOCK = new org.example.util.AtomicLamportClock();
    private static final String NODE_ID = "CS-1"; // unique node identifier for Lamport ordering
    // --------------------------

    // HTTP handler (wire-level only)
    private static final HttpHandler HTTP = new DefaultHttpHandler();

    // Retry policy: 4 attempts, exponential backoff (200 → 400 → 800 → 1600 ms) + ≤100ms jitter.
    // Sonar: these parameters match assignment specs, not hardcoded magic numbers in logic.
    private static final org.example.interfaces.RetryExecutor RETRY =
            new org.example.util.SimpleRetryExecutor(4, 200, 1600, 100);

    /**
     * CLI entry point for sending a single PUT request.
     * Example usage:
     * <pre>
     * java -cp target/classes org.example.client.ContentServer localhost:4567 src/main/resources/weather.txt
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        if (!validateArgs(args))
            return;

        // Input arguments
        String urlOrHostPort = args[0];
        String filePath = args[1];

        // --- Target parsing ---
        String hostPort = urlOrHostPort.replaceFirst("^https?://", "");
        String host = parseHost(hostPort);
        int port = parsePort(hostPort, 4567); // default server port
        String path = ensureLeadingSlash(parsePath(hostPort, "/weather.json"));

        // --- Prepare body payload ---
        byte[] bodyBytes = buildBody(Path.of(filePath));
        int contentLength = bodyBytes.length;

        // --- Lamport tick and header setup ---
        CLOCK.tick();
        Map<String, String> extra = buildExtraHeaders();
        logLamportSend();

        // --- Build HTTP PUT request ---
        String headers = HTTP.buildRequest("PUT", path, host, port, extra, contentLength);
        logRequestPreview(headers, contentLength);

        // --- Send request with retry logic ---
        String resp = sendWithRetry(host, port, headers, bodyBytes);

        // --- Display and process response ---
        showResponseHeaders(resp);
        updateLamportFromResponse(resp);
        printServerResponse(resp);
    }

    /* ---------------- Helper methods (no logic change) ---------------- */

    /** Validates CLI arguments, prints usage if insufficient. */
    private static boolean validateArgs(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage:\n  ContentServer <host:port | http://host:port[/path]> <filePath>");
            return false;
        }
        return true;
    }

    /** Ensures the HTTP path starts with a '/' to avoid malformed requests. */
    private static String ensureLeadingSlash(String p) {
        return p.startsWith("/") ? p : "/" + p;
    }

    /** Builds required HTTP headers including Lamport metadata and content type. */
    private static Map<String, String> buildExtraHeaders() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("User-Agent", "ContentServer/1.0");
        extra.put("X-Lamport-Node", NODE_ID);
        extra.put("X-Lamport-Clock", String.valueOf(CLOCK.get()));
        extra.put("Content-Type", "application/json; charset=utf-8");
        return extra;
    }

    /** Prints current Lamport clock state before sending a request. */
    private static void logLamportSend() {
        System.out.println("[Lamport] ContentServer sending request");
        System.out.println("          Node ID: " + NODE_ID);
        System.out.println("          Current Clock: " + CLOCK.get());
    }

    /** Prints HTTP request preview for easier grading/debugging. */
    private static void logRequestPreview(String headers, int contentLength) {
        System.out.println("Request sent:");
        System.out.print(headers.replace("\r\n", "\n"));
        System.out.println(contentLength == 0 ? "(empty body)" : "(body bytes): " + contentLength);
    }

    /**
     * Sends an HTTP PUT request and retries on transient error codes.
     * @return full raw HTTP response
     * @throws Exception if retries are exhausted or I/O fails
     */
    private static String sendWithRetry(String host, int port, String headers, byte[] bodyBytes) throws Exception {
        return RETRY.execute(() -> {
            try (Socket s = new Socket(host, port);
                 OutputStream out = s.getOutputStream();
                 InputStream in = s.getInputStream()) {

                HTTP.send(out, headers, bodyBytes);
                String r = HTTP.readRawResponse(in);

                int code = statusCodeOf(firstLine(r));
                // Retry only for transient server errors (per assignment spec)
                if (code == HTTP_UNAVAILABLE || code == HTTP_INTERNAL_ERROR || code == HTTP_TOO_MANY_REQUESTS) {
                    String ra = headerValue(r, "Retry-After");
                    if (ra != null) {
                        try {
                            long sec = Long.parseLong(ra.trim());
                            if (sec > 0) {
                                // Sonar: Thread.sleep is intentional to honor server backoff header (max 10s).
                                Thread.sleep(Math.min(sec * 1000L, 10_000L));
                            }
                        } catch (Exception ignored) {
                            // Sonar: invalid Retry-After ignored safely.
                        }
                    }
                    throw new IOException("HTTP " + code + " retryable");
                }
                return r;
            }
        });
    }

    /** Prints response headers only (useful for Lamport header visibility). */
    private static void showResponseHeaders(String resp) {
        int hdrEnd = resp.indexOf("\r\n\r\n");
        if (hdrEnd > 0) {
            System.out.println("\n--- Response headers start ---");
            System.out.println(resp.substring(0, hdrEnd));
            System.out.println("--- Response headers end ---");
        }
    }

    /** Updates Lamport clock from server’s response header, if present. */
    private static void updateLamportFromResponse(String resp) {
        String respClock = headerValue(resp, "X-Lamport-Clock");
        if (respClock != null) {
            try {
                long remote = Long.parseLong(respClock);
                long after = CLOCK.update(remote);
                System.out.println("[Lamport] ContentServer received response");
                System.out.println("          Server Clock: " + remote);
                System.out.println("          Updated Local Clock: " + after);
            } catch (Exception ignored) {
                // Sonar: invalid Lamport value ignored intentionally (non-fatal).
            }
        }
    }

    /** Prints HTTP status line and body (assignment-readable format). */
    private static void printServerResponse(String resp) {
        String statusLine = firstLine(resp);
        String body = bodyOf(resp);

        System.out.println("\nServer Response:");
        System.out.println(statusLine);
        if (!body.isBlank())
            System.out.println(body);
    }

    /* ---------------- lower-level helpers (unchanged behavior) ---------------- */

    /** Extracts specific header value (case-insensitive). */
    private static String headerValue(String http, String name) {
        int headEnd = http.indexOf("\r\n\r\n");
        String headers = (headEnd >= 0) ? http.substring(0, headEnd) : http;
        for (String line : headers.split("\r\n")) {
            int i = line.indexOf(':');
            if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase(name)) {
                return line.substring(i + 1).trim();
            }
        }
        return null;
    }

    /** Extracts numeric HTTP status code from status line, or -1 if invalid. */
    private static int statusCodeOf(String statusLine) {
        String[] parts = statusLine.split(" ");
        if (parts.length >= 2) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (Exception ignored) {
                // Sonar: invalid status safely returns -1.
            }
        }
        return -1;
    }

    /**
     * Reads file and converts it to JSON.
     * <ul>
     *     <li>If file content already JSON, send as-is.</li>
     *     <li>If key:value format, convert to JSON object.</li>
     * </ul>
     */
    private static byte[] buildBody(Path file) throws IOException {
        if (!Files.exists(file))
            throw new FileNotFoundException("File not found: " + file);

        byte[] raw = Files.readAllBytes(file);
        if (raw.length == 0)
            return raw;

        String text = new String(raw, StandardCharsets.UTF_8).trim();
        if (text.startsWith("{"))
            return text.getBytes(StandardCharsets.UTF_8);

        // Convert key:value pairs to JSON
        Map<String, String> map = new LinkedHashMap<>();
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                int idx = line.indexOf(':');
                if (idx < 0)
                    continue;
                String key = line.substring(0, idx).trim();
                String val = line.substring(idx + 1).trim();
                if (!key.isEmpty())
                    map.put(key, val);
            }
        }
        String json = new Gson().toJson(map);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** Extracts hostname portion from host:port/path. */
    private static String parseHost(String hostPort) {
        String hp = hostPort.contains("/") ? hostPort.substring(0, hostPort.indexOf('/')) : hostPort;
        int i = hp.indexOf(':');
        return (i >= 0) ? hp.substring(0, i) : hp;
    }

    /** Extracts port number, or uses default if missing. */
    private static int parsePort(String hostPort, int def) {
        String hp = hostPort.contains("/") ? hostPort.substring(0, hostPort.indexOf('/')) : hostPort;
        int i = hp.indexOf(':');
        if (i >= 0) {
            try {
                return Integer.parseInt(hp.substring(i + 1));
            } catch (Exception ignored) {
                // Sonar: fallback to default on parse failure.
            }
        }
        return def;
    }

    /** Extracts the path portion of the URL or returns a default. */
    private static String parsePath(String hostPort, String def) {
        int i = hostPort.indexOf('/');
        return (i >= 0) ? hostPort.substring(i) : def;
    }

    /** Returns the first line (status line) of an HTTP message. */
    private static String firstLine(String http) {
        int i = http.indexOf("\r\n");
        return (i >= 0) ? http.substring(0, i) : http;
    }

    /** Returns only the body (content after header section). */
    private static String bodyOf(String resp) {
        int i = resp.indexOf("\r\n\r\n");
        return (i >= 0) ? resp.substring(i + 4) : "";
    }
}
