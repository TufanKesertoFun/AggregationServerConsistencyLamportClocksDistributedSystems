package org.example.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GetClient implements org.example.interfaces.GetClient {

    private static final Gson GSON = new Gson();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // --- Lamport additions ---
    private static final org.example.interfaces.LamportClock CLOCK =
            new org.example.util.AtomicLamportClock();
    private static final String NODE_ID = "GET-1";
    // --------------------------

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: GETClient <http://host:port[/path]> OR <host:port> [path]");
            System.out.println("Example: GETClient localhost:4567 /weather.json");
            return;
        }
        String urlOrHostPort = args[0];
        String pathOrNull = (args.length >= 2) ? args[1] : null;

        String pretty = new GetClient().fetch(urlOrHostPort, pathOrNull);
        if (!pretty.isEmpty()) {
            System.out.println("\nServer Response:");
            System.out.println(pretty);
        }
    }

    @Override
    public String fetch(String urlOrHostPort, String pathOrNull) throws Exception {
        String hostPort = urlOrHostPort.replaceFirst("^https?://", "");
        String host = parseHost(hostPort);
        int port = parsePort(hostPort, 4567);
        String path = (pathOrNull != null) ? pathOrNull : parsePath(hostPort, "/weather.json");
        if (path != null && !path.startsWith("/")) path = "/" + path;

        try (Socket s = new Socket(host, port);
             OutputStream out = s.getOutputStream();
             InputStream in = s.getInputStream()) {

            // --- Lamport: tick before sending and include headers ---
            CLOCK.tick();
            String req =
                    "GET " + path + " HTTP/1.1\r\n" +
                            "Host: " + host + ":" + port + "\r\n" +
                            "X-Lamport-Node: " + NODE_ID + "\r\n" +
                            "X-Lamport-Clock: " + CLOCK.get() + "\r\n" +
                            "Connection: close\r\n\r\n";
            // -------------------------------------------------------

            System.out.println("Request sent:");
            System.out.print(req.replace("\r\n", "\n"));

            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String resp = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            // --- Lamport: update from server’s clock (if present) ---
            String respClock = headerValue(resp, "X-Lamport-Clock");
            if (respClock != null) {
                try { CLOCK.update(Long.parseLong(respClock)); } catch (Exception ignored) {}
            }
            // --------------------------------------------------------

            String statusLine = statusLineOf(resp);
            int status = statusCodeOf(statusLine);
            String body = bodyOf(resp);

            switch (status) {
                case 200 -> {
                    try {
                        return status + " " + reasonOf(statusLine) + "\n" + toPrettyAllStrings(body);
                    } catch (Exception e) {
                        return status + " " + reasonOf(statusLine) + "\n" + body;
                    }
                }
                case 201 -> { return "201 Created\n" + (body.isBlank() ? "" : body); }
                case 204 -> { return "204 No Content\nNo weather data available."; }
                case 400 -> { return "400 Bad Request\n" + (body.isBlank() ? "Your request was invalid." : body); }
                case 404 -> { return "404 Not Found\nWeather data not found."; }
                case 500 -> { return "500 Internal Server Error\n" + (body.isBlank() ? "Server failed to process the request." : body); }
                default -> {
                    if (status < 0) {
                        return "Unexpected response (couldn’t parse status line):\n" + statusLine + "\n" + body;
                    } else {
                        return status + " " + reasonOf(statusLine) + "\n" + body;
                    }
                }
            }
        }
    }

    // ---- helpers ----
    private static String parseHost(String hostPort) {
        String hp = hostPort.contains("/") ? hostPort.substring(0, hostPort.indexOf('/')) : hostPort;
        int i = hp.indexOf(':'); return (i >= 0) ? hp.substring(0, i) : hp;
    }
    private static int parsePort(String hostPort, int def) {
        String hp = hostPort.contains("/") ? hostPort.substring(0, hostPort.indexOf('/')) : hostPort;
        int i = hp.indexOf(':'); if (i >= 0) { try { return Integer.parseInt(hp.substring(i + 1)); } catch (Exception ignored) {} }
        return def;
    }
    private static String parsePath(String hostPort, String def) {
        int i = hostPort.indexOf('/'); return (i >= 0) ? hostPort.substring(i) : def;
    }
    private static String bodyOf(String resp) { int i = resp.indexOf("\r\n\r\n"); return (i >= 0) ? resp.substring(i + 4) : ""; }
    private static String statusLineOf(String resp) { int i = resp.indexOf("\r\n"); return (i >= 0) ? resp.substring(0, i) : resp; }
    private static int statusCodeOf(String statusLine) {
        String[] parts = statusLine.split(" "); if (parts.length >= 2) { try { return Integer.parseInt(parts[1]); } catch (Exception ignored) {} }
        return -1;
    }
    private static String reasonOf(String statusLine) {
        int first = statusLine.indexOf(' '), second = statusLine.indexOf(' ', first + 1);
        return (first >= 0 && second > first) ? statusLine.substring(second + 1).trim() : "";
    }
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
    private static String toPrettyAllStrings(String jsonBody) {
        Map<String, Object> original = GSON.fromJson(jsonBody, new TypeToken<LinkedHashMap<String, Object>>(){}.getType());
        LinkedHashMap<String, String> asStrings = new LinkedHashMap<>();
        if (original != null) for (String k : original.keySet()) {
            Object v = original.get(k); asStrings.put(k, (v == null) ? "null" : String.valueOf(v));
        }
        return PRETTY.toJson(asStrings);
    }
}
