package org.example.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import org.example.interfaces.HttpHandler;
import org.example.http.DefaultHttpHandler;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GetClient implements org.example.interfaces.GetClient {

    private static final Gson GSON = new Gson();
    private static final Gson PRETTY =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // --- Lamport additions ---
    private static final org.example.interfaces.LamportClock CLOCK =
            new org.example.util.AtomicLamportClock();
    private static final String NODE_ID = "GET-1";
    // --------------------------

    private static final HttpHandler HTTP = new DefaultHttpHandler();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: GETClient <http://host:port[/path]> OR <host:port> [path]");
            System.out.println("Example: GETClient localhost:4567 /weather.json");
            return;
        }
        String urlOrHostPort = args[0];
        String pathOrNull = (args.length >= 2) ? args[1] : null;

        String response = new GetClient().fetch(urlOrHostPort, pathOrNull);
        if (!response.isEmpty()) {
            System.out.println("\nServer Response:");
            System.out.println(response);
        }
    }

    @Override
    public String fetch(String urlOrHostPort, String pathOrNull) throws Exception {
        String hostPort = urlOrHostPort.replaceFirst("^https?://", "");
        String host = parseHost(hostPort);
        int port = parsePort(hostPort, 4567);
        String path = (pathOrNull != null) ? pathOrNull : parsePath(hostPort, "/weather.json");
        if (!path.startsWith("/")) path = "/" + path;

        CLOCK.tick();
        Map<String, String> extra = new LinkedHashMap<>();
        // --- Lamport human-readable log before sending ---
        System.out.println("[Lamport] GetClient sending request");
        System.out.println("          Node ID: " + NODE_ID);
        System.out.println("          Current Clock: " + CLOCK.get());


        extra.put("X-Lamport-Node", NODE_ID);
        extra.put("X-Lamport-Clock", String.valueOf(CLOCK.get()));
        extra.put("Connection", "close");

        String req = HTTP.buildRequest("GET", path, host, port, extra, 0);

        System.out.println("Request sent:");
        System.out.print(req.replace("\r\n", "\n"));

        try (Socket socket = new Socket(host, port);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {

            HTTP.send(out, req, new byte[0]);
            String resp = HTTP.readRawResponse(in);

            // --- Lamport clock update ---
            String respClock = headerValue(resp, "X-Lamport-Clock");
            if (respClock != null) {
                try {
                    long remote = Long.parseLong(respClock);
                    long after = CLOCK.update(remote);

                    // --- Lamport human-readable log after receiving ---
                    System.out.println("[Lamport] GetClient received response");
                    System.out.println("          Remote (Server) Clock: " + remote);
                    System.out.println("          Updated Local Clock: " + after);
                    // ---------------------------------------------------
                } catch (Exception ignored) {}
            }

            // ----------------------------

            String statusLine = statusLineOf(resp);
            int status = statusCodeOf(statusLine);
            String reason = HTTP.reason(status); // <-- used for all codes
            String body = bodyOf(resp);

            // Try pretty-printing JSON body (works for any status)
            try {
                return status + " " + reason + "\n" + toPrettyAllStrings(body);
            } catch (Exception ignored) {
                return status + " " + reason + "\n" + body;
            }
        }
    }

    // ---- helpers ----
    private static String parseHost(String hostPort) {
        String hp = hostPort.contains("/") ? hostPort.substring(0, hostPort.indexOf('/')) : hostPort;
        int i = hp.indexOf(':');
        return (i >= 0) ? hp.substring(0, i) : hp;
    }
    private static int parsePort(String hostPort, int def) {
        String hp = hostPort.contains("/") ? hostPort.substring(0, hostPort.indexOf('/')) : hostPort;
        int i = hp.indexOf(':');
        if (i >= 0) {
            try { return Integer.parseInt(hp.substring(i + 1)); } catch (Exception ignored) {}
        }
        return def;
    }
    private static String parsePath(String hostPort, String def) {
        int i = hostPort.indexOf('/');
        return (i >= 0) ? hostPort.substring(i) : def;
    }

    private static String bodyOf(String resp) {
        int i = resp.indexOf("\r\n\r\n");
        return (i >= 0) ? resp.substring(i + 4) : "";
    }
    private static String statusLineOf(String resp) {
        int i = resp.indexOf("\r\n");
        return (i >= 0) ? resp.substring(0, i) : resp;
    }
    private static int statusCodeOf(String statusLine) {
        String[] parts = statusLine.split(" ");
        if (parts.length >= 2) {
            try { return Integer.parseInt(parts[1]); } catch (Exception ignored) {}
        }
        return -1;
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
        Map<String, Object> original =
                GSON.fromJson(jsonBody, new TypeToken<LinkedHashMap<String, Object>>() {}.getType());
        LinkedHashMap<String, String> asStrings = new LinkedHashMap<>();
        if (original != null) {
            for (String k : original.keySet()) {
                Object v = original.get(k);
                asStrings.put(k, (v == null) ? "null" : String.valueOf(v));
            }
        }
        return PRETTY.toJson(asStrings);
    }
}
