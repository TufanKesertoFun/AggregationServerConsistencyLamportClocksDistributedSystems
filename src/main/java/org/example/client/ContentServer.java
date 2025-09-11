package org.example.client;

import com.google.gson.JsonObject;  // only for building JSON

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ContentServer {

    private static final String USER_AGENT = "ATOMClient/1/0";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: ContentServer <host:port> <relative-or-absolute-file-path>");
            System.out.println("Example: ContentServer localhost:4567 src/main/resources/weather.txt");
            return;
        }

        // --- 1) Parse CLI ---
        String[] hp = args[0].split(":");
        String host = hp[0];
        int port = (hp.length > 1) ? Integer.parseInt(hp[1]) : 4567;
        Path filePath = Path.of(args[1]);

        // --- 2) Load key:value pairs and build JSON payload ---
        Map<String, String> fields = loadKeyValues(filePath);
        String json = buildJson(fields);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        // --- 3) PUT /weather.json ---
        String putStatus = sendPut(host, port, "/weather.json", body);
        System.out.println("[PUT] " + putStatus);
    }

    /** station file parser: lines like `id: IDS60901` (ignores blank lines and # comments) */
    private static Map<String, String> loadKeyValues(Path file) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line; int ln = 0;
            while ((line = br.readLine()) != null) {
                ln++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                int idx = line.indexOf(':');
                if (idx < 0) idx = line.indexOf('='); // allow key=value too
                if (idx < 0) throw new IllegalArgumentException("Bad line " + ln + ": " + line + " (expected key:value)");

                String key = line.substring(0, idx).trim();
                String val = line.substring(idx + 1).trim();

                // strip optional wrapping quotes
                if (val.length() >= 2 && (
                        (val.startsWith("\"") && val.endsWith("\"")) ||
                                (val.startsWith("'") && val.endsWith("'")))) {
                    val = val.substring(1, val.length() - 1);
                }
                map.put(key, val);
            }
        }
        return map;
    }

    /** Build JSON. Tries to coerce numbers/booleans if they look like them; otherwise keeps strings. */
    private static String buildJson(Map<String, String> fields) {
        JsonObject obj = new JsonObject();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();

            if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
                obj.addProperty(k, Boolean.parseBoolean(v));
            } else if (v.matches("-?\\d+")) {
                try { obj.addProperty(k, Long.parseLong(v)); }
                catch (NumberFormatException ex) { obj.addProperty(k, v); }
            } else if (v.matches("-?\\d+\\.\\d+")) {
                try { obj.addProperty(k, Double.parseDouble(v)); }
                catch (NumberFormatException ex) { obj.addProperty(k, v); }
            } else {
                obj.addProperty(k, v);
            }
        }
        return obj.toString();
    }

    /** Send the PUT and return the HTTP status line. */
    private static String sendPut(String host, int port, String path, byte[] body) throws IOException {
        try (Socket sock = new Socket(host, port);
             OutputStream out = sock.getOutputStream();
             InputStream in = sock.getInputStream()) {

            String req =
                    "PUT " + path + " HTTP/1.1\r\n" +
                            "Host: " + host + ":" + port + "\r\n" +
                            "User-Agent: " + USER_AGENT + "\r\n" +
                            "Content-Type: application/json; charset=UTF-8\r\n" +
                            "Content-Length: " + body.length + "\r\n" +
                            "Connection: close\r\n\r\n";

            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();

            return readStatusLine(in);
        }
    }

    /** Read only the status line from the HTTP response. */
    private static String readStatusLine(InputStream in) throws IOException {
        String resp = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        int sep = resp.indexOf("\r\n");
        return (sep > 0) ? resp.substring(0, sep) : resp;
    }
}
