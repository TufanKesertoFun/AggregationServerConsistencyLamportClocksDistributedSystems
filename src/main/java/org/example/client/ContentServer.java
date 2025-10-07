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

public final class ContentServer {
    // --- Lamport additions ---
    private static final org.example.interfaces.LamportClock CLOCK =
            new org.example.util.AtomicLamportClock();
    private static final String NODE_ID = "CS-1";
    // --------------------------

    // HTTP handler (wire-level only)
    private static final HttpHandler HTTP = new DefaultHttpHandler();

    // Retry (4 attempts, 200→400→800→1600ms + ≤100ms jitter)
    private static final org.example.interfaces.RetryExecutor RETRY =
            new org.example.util.SimpleRetryExecutor(4, 200, 1600, 100);

    public static void main(String[] args) throws Exception {
        if (!validateArgs(args)) return;

        // inputs
        String urlOrHostPort = args[0];
        String filePath = args[1];

        // parse target
        String hostPort = urlOrHostPort.replaceFirst("^https?://", "");
        String host = parseHost(hostPort);
        int port = parsePort(hostPort, 4567);
        String path = ensureLeadingSlash(parsePath(hostPort, "/weather.json"));

        // body
        byte[] bodyBytes = buildBody(Path.of(filePath));
        int contentLength = bodyBytes.length;

        // lamport + headers
        CLOCK.tick();
        Map<String, String> extra = buildExtraHeaders();
        logLamportSend();

        // request
        String headers = HTTP.buildRequest("PUT", path, host, port, extra, contentLength);
        logRequestPreview(headers, contentLength);

        // send with retry
        String resp = sendWithRetry(host, port, headers, bodyBytes);

        // show headers
        showResponseHeaders(resp);

        // lamport update
        updateLamportFromResponse(resp);

        // print final response
        printServerResponse(resp);
    }

    /* ---------------- extracted private helpers (no logic change) ---------------- */

    private static boolean validateArgs(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage:\n  ContentServer <host:port | "
                    + "http://host:port[/path]> <filePath>");
            return false;
        }
        return true;
    }

    private static String ensureLeadingSlash(String p) {
        return p.startsWith("/") ? p : "/" + p;
    }

    private static Map<String, String> buildExtraHeaders() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("User-Agent", "ContentServer/1.0");
        extra.put("X-Lamport-Node", NODE_ID);
        extra.put("X-Lamport-Clock", String.valueOf(CLOCK.get()));
        extra.put("Content-Type", "application/json; charset=utf-8");
        return extra;
    }

    private static void logLamportSend() {
        System.out.println("[Lamport] ContentServer sending request");
        System.out.println("          Node ID: " + NODE_ID);
        System.out.println("          Current Clock: " + CLOCK.get());
    }

    private static void logRequestPreview(String headers, int contentLength) {
        System.out.println("Request sent:");
        System.out.print(headers.replace("\r\n", "\n"));
        System.out.println(contentLength == 0 ? "(empty body)" : "(body bytes): " + contentLength);
    }

    private static String sendWithRetry(String host, int port, String headers, byte[] bodyBytes) throws Exception {
        return RETRY.execute(() -> {
            try (Socket s = new Socket(host, port);
                 OutputStream out = s.getOutputStream();
                 InputStream in = s.getInputStream()) {

                HTTP.send(out, headers, bodyBytes);
                String r = HTTP.readRawResponse(in);

                int code = statusCodeOf(firstLine(r));
                if (code == 503 || code == 500 || code == 429) {
                    String ra = headerValue(r, "Retry-After");
                    if (ra != null) {
                        try {
                            long sec = Long.parseLong(ra.trim());
                            if (sec > 0) Thread.sleep(Math.min(sec * 1000L, 10_000L));
                        } catch (Exception ignored) {}
                    }
                    throw new IOException("HTTP " + code + " retryable");
                }
                return r;
            }
        });
    }

    private static void showResponseHeaders(String resp) {
        int hdrEnd = resp.indexOf("\r\n\r\n");
        if (hdrEnd > 0) {
            System.out.println("\n--- Response headers start ---");
            System.out.println(resp.substring(0, hdrEnd));
            System.out.println("--- Response headers end ---");
        }
    }

    private static void updateLamportFromResponse(String resp) {
        String respClock = headerValue(resp, "X-Lamport-Clock");
        if (respClock != null) {
            try {
                long remote = Long.parseLong(respClock);
                long after = CLOCK.update(remote);
                System.out.println("[Lamport] ContentServer received response");
                System.out.println("          Server Clock: " + remote);
                System.out.println("          Updated Local Clock: " + after);
            } catch (Exception ignored) { }
        }
    }

    private static void printServerResponse(String resp) {
        String statusLine = firstLine(resp);
        String body = bodyOf(resp);

        System.out.println("\nServer Response:");
        System.out.println(statusLine);
        if (!body.isBlank())
            System.out.println(body);
    }

    /* ---------------- existing helpers (unchanged) ---------------- */
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

    private static int statusCodeOf(String statusLine) {
        String[] parts = statusLine.split(" ");
        if (parts.length >= 2) {
            try { return Integer.parseInt(parts[1]); } catch (Exception ignored) {}
        }
        return -1;
    }

    private static byte[] buildBody(Path file) throws IOException {
        if (!Files.exists(file))
            throw new FileNotFoundException("File not found: " + file);
        byte[] raw = Files.readAllBytes(file);
        if (raw.length == 0)
            return raw;
        String text = new String(raw, StandardCharsets.UTF_8).trim();
        if (text.startsWith("{"))
            return text.getBytes(StandardCharsets.UTF_8);

        Map<String, String> map = new LinkedHashMap<>();
        try (BufferedReader br =
                     Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
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

    private static String parseHost(String hostPort) {
        String hp = hostPort.contains("/")
                ? hostPort.substring(0, hostPort.indexOf('/'))
                : hostPort;
        int i = hp.indexOf(':');
        return (i >= 0) ? hp.substring(0, i) : hp;
    }
    private static int parsePort(String hostPort, int def) {
        String hp = hostPort.contains("/")
                ? hostPort.substring(0, hostPort.indexOf('/'))
                : hostPort;
        int i = hp.indexOf(':');
        if (i >= 0) {
            try {
                return Integer.parseInt(hp.substring(i + 1));
            } catch (Exception ignored) {
            }
        }
        return def;
    }
    private static String parsePath(String hostPort, String def) {
        int i = hostPort.indexOf('/');
        return (i >= 0) ? hostPort.substring(i) : def;
    }
    private static String firstLine(String http) {
        int i = http.indexOf("\r\n");
        return (i >= 0) ? http.substring(0, i) : http;
    }
    private static String bodyOf(String resp) {
        int i = resp.indexOf("\r\n\r\n");
        return (i >= 0) ? resp.substring(i + 4) : "";
    }
}
