package org.example.server;

import org.example.persistance.FileSnapshotStore;
import org.example.interfaces.SnapshotStore;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class AggregationServer {
    // keep the last payload in memory (start empty)
    private static volatile String lastPayload = null;

    private static final SnapshotStore STORE =
            new FileSnapshotStore(java.nio.file.Paths.get("src","main","resources","temp"), "weather");

    public static void main(String[] args) throws Exception {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : 4567;

        String snap = STORE.load();
        if (snap != null && !snap.isBlank()){
            lastPayload = snap;
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
            if (headerLines.length == 0) { writeEmpty(out, 400); return; }

            String requestLine = headerLines[0];
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { writeEmpty(out, 400); return; }
            String method = parts[0];
            String path   = parts[1];

            int contentLength = contentLengthFrom(headerLines);

            // PUT /weather.json
            if ("PUT".equals(method) && "/weather.json".equals(path)) {
                if (contentLength <= 0) { writeEmpty(out, 204); return; }
                byte[] body = readBody(in, contentLength);
                String json = new String(body, StandardCharsets.UTF_8);

                // Minimal validation for 500 on invalid JSON / missing id
                try {
                    validateJsonOrThrow(json);
                } catch (Exception e) {
                    writeJson(out, 500, "{\"error\":\"invalid JSON or missing id\"}");
                    return;
                }

                boolean first = (lastPayload == null || lastPayload.isBlank());
                lastPayload = json;
                STORE.save(lastPayload);

                writeEmpty(out, first ? 201 : 200); // 201 first time, then 200
                return;
            }

            // GET /weather.json
            if ("GET".equals(method) && "/weather.json".equals(path)) {
                if (lastPayload == null || lastPayload.isBlank()) {
                    byte[] b = "{\"error\":\"no weather data available\"}".getBytes(StandardCharsets.UTF_8);
                    out.write(("HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: "
                            + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(b);
                } else {
                    writeJson(out, 200, lastPayload);
                }
                return;
            }

            // any other request → 400 (per spec simplification)
            writeEmpty(out, 400);
        } catch (Exception ignore) {
            // keep server alive
        }
    }

    /* -------- tiny JSON validator for PUT -------- */
    private static void validateJsonOrThrow(String json) throws Exception {
        var element = JsonParser.parseString(json);
        if (!element.isJsonObject()) throw new IllegalArgumentException("not a JSON object");
        JsonObject obj = element.getAsJsonObject();

        if (!obj.has("id")) throw new IllegalArgumentException("missing id");
        if (obj.get("id").isJsonNull()) throw new IllegalArgumentException("null id");
        String id = obj.get("id").getAsString();
        if (id == null || id.isBlank()) throw new IllegalArgumentException("blank id");
    }

    /* -------------------- helpers -------------------- */

    /** Read request headers until CRLFCRLF and return lines. */
    private static String[] readHeaderLines(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0, b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') break; // end headers
            else state = 0;
        }
        String headersStr = buf.toString(StandardCharsets.UTF_8);
        return headersStr.isEmpty() ? new String[0] : headersStr.split("\r\n");
    }

    /** Find Content-Length header (case-insensitive). */
    private static int contentLengthFrom(String[] headerLines) {
        for (String line : headerLines) {
            if (line == null) continue;
            String lower = line.toLowerCase();
            if (lower.startsWith("content-length:")) {
                try { return Integer.parseInt(line.substring(15).trim()); }
                catch (Exception ignored) {}
            }
        }
        return 0;
    }

    /** Read exactly len bytes of body. */
    private static byte[] readBody(InputStream in, int len) throws IOException {
        return in.readNBytes(len);
    }

    /** Write an empty (no body) response with the given status. */
    private static void writeEmpty(OutputStream out, int status) throws IOException {
        String res =
                "HTTP/1.1 " + status + " " + reason(status) + "\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(res.getBytes(StandardCharsets.UTF_8));
    }

    /** Write a JSON body response with 200/… */
    private static void writeJson(OutputStream out, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String res =
                "HTTP/1.1 " + status + " " + reason(status) + "\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: " + body.length + "\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(res.getBytes(StandardCharsets.UTF_8));
        out.write(body);
    }

    /** Minimal reason phrase mapper (kept local to avoid extra files). */
    private static String reason(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            default -> "OK";
        };
    }
}
