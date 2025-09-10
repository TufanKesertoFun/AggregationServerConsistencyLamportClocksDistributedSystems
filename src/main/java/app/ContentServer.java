package app;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/** Usage: java app.ContentServer http://localhost:4567 path/to/input.txt [sourceId] */
public class ContentServer {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java app.ContentServer <serverUrl> <filePath> [sourceId]");
            System.exit(2);
        }
        String base = stripSlash(args[0]);
        String filePath = args[1];
        String sourceId = (args.length >= 3) ? args[2] : "content-server-1";

        // parse simple "key:value" file from the spec
        Map<String,Object> m = new LinkedHashMap<>();
        try (var br = Files.newBufferedReader(Paths.get(filePath), StandardCharsets.UTF_8)) {
            for (String line; (line = br.readLine()) != null; ) {
                line = line.trim();
                if (line.isEmpty() || !line.contains(":")) continue;
                int idx = line.indexOf(':');
                String k = line.substring(0, idx).trim();
                String v = line.substring(idx + 1).trim();
                // normalize keys with underscores to match JSON example style
                m.put(k.replace(' ', '_'), v);
            }
        }

        if (!m.containsKey("id") || String.valueOf(m.get("id")).isBlank()) {
            System.err.println("Invalid feed: missing 'id'");
            System.exit(3);
        }
        // helpful hints for your server (it will also read headers)
        m.putIfAbsent("sourceId", sourceId);
        m.putIfAbsent("lamport", 0);

        String json = new Gson().toJson(m);

        HttpURLConnection c = (HttpURLConnection) new URL(base + "/weather.json").openConnection();
        c.setRequestMethod("PUT");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("X-Source-Id", sourceId);
        c.setRequestProperty("X-Lamport", "0");

        try (OutputStream os = c.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        int code = c.getResponseCode();
        System.out.println("HTTP " + code + " " + c.getResponseMessage());
        try (var br = new BufferedReader(new InputStreamReader(
                code >= 400 ? c.getErrorStream() : c.getInputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = br.readLine()) != null) System.out.println(line);
        }
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length()-1) : s;
    }
}
