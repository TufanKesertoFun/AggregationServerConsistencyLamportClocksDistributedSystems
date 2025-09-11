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

public final class ContentServer {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage:\n  ContentServer <host:port | http://host:port[/path]> <filePath>");
            return;
        }

        String urlOrHostPort = args[0];
        String filePath = args[1];

        // Parse host, port, and path (default /weather.json)
        String hostPort = urlOrHostPort.replaceFirst("^https?://", "");
        String host = parseHost(hostPort);
        int port = parsePort(hostPort, 4567);
        String path = parsePath(hostPort, "/weather.json");
        if (!path.startsWith("/")) path = "/" + path;

        // Build request body: accept either JSON file OR key:value text file
        byte[] bodyBytes = buildBody(Path.of(filePath));
        int contentLength = bodyBytes.length;

        String headers =
                "PUT " + path + " HTTP/1.1\r\n" +
                        "Host: " + host + ":" + port + "\r\n" +
                        "User-Agent: ContentServer/1.0\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: " + contentLength + "\r\n" +
                        "Connection: close\r\n\r\n";

        System.out.println("Request sent:");
        System.out.print(headers.replace("\r\n", "\n"));
        System.out.println(contentLength == 0 ? "(empty body)" : "(body bytes): " + contentLength);

        try (Socket s = new Socket(host, port);
             OutputStream out = s.getOutputStream();
             InputStream in = s.getInputStream()) {

            out.write(headers.getBytes(StandardCharsets.UTF_8));
            if (contentLength > 0) out.write(bodyBytes);
            out.flush();

            String resp = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String statusLine = firstLine(resp);
            String body = bodyOf(resp);

            System.out.println("\nServer Response:");
            System.out.println(statusLine);
            if (!body.isBlank()) System.out.println(body);
        }
    }

    /* ---------------- helpers ---------------- */

    private static byte[] buildBody(Path file) throws IOException {
        if (!Files.exists(file)) throw new FileNotFoundException("File not found: " + file);

        byte[] raw = Files.readAllBytes(file);
        if (raw.length == 0) return raw; // empty file -> 204 on server

        String text = new String(raw, StandardCharsets.UTF_8).trim();
        // If it already looks like JSON, send as-is
        if (text.startsWith("{")) return text.getBytes(StandardCharsets.UTF_8);

        // Else assume key:value lines -> convert to JSON
        Map<String, String> map = new LinkedHashMap<>();
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                int idx = line.indexOf(':');
                if (idx < 0) continue;
                String key = line.substring(0, idx).trim();
                String val = line.substring(idx + 1).trim();
                if (!key.isEmpty()) map.put(key, val);
            }
        }
        String json = new Gson().toJson(map);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String parseHost(String hostPort) {
        String hp = hostPort.contains("/") ? hostPort.substring(0, hostPort.indexOf('/')) : hostPort;
        int i = hp.indexOf(':');
        return (i >= 0) ? hp.substring(0, i) : hp;
    }

    private static int parsePort(String hostPort, int def) {
        String hp = hostPort.contains("/") ? hostPort.substring(0, hostPort.indexOf('/')) : hostPort;
        int i = hp.indexOf(':');
        if (i >= 0) { try { return Integer.parseInt(hp.substring(i + 1)); } catch (Exception ignored) {} }
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
