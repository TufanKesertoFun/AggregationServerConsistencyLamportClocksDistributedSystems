package app;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Usage: java app.GETClient http://localhost:4567 [stationId] */
public class GETClient {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java app.GETClient <serverUrl> [stationId]");
            System.exit(2);
        }
        String base = stripSlash(args[0]);
        String stationIdFilter = (args.length >= 2) ? args[1] : null;

        HttpURLConnection c = (HttpURLConnection) new URL(base + "/weather.json").openConnection();
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json; charset=utf-8");
        // simple Lamport header (optional)
        c.setRequestProperty("X-Source-Id", "get-client");
        c.setRequestProperty("X-Lamport", "0");

        int code = c.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(
                code >= 400 ? c.getErrorStream() : c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (String line; (line = br.readLine()) != null; ) sb.append(line);
        br.close();

        if (code != 200) {
            System.out.println("HTTP " + code + " " + c.getResponseMessage());
            System.out.println(sb);
            return;
        }

        Gson gson = new Gson();
        Type T = new TypeToken<Map<String, Map<String, Object>>>(){}.getType();
        Map<String, Map<String, Object>> all = gson.fromJson(sb.toString(), T);

        if (stationIdFilter != null && !stationIdFilter.isBlank()) {
            Map<String,Object> rec = all.get(stationIdFilter);
            if (rec == null) {
                System.out.println("No data for station: " + stationIdFilter);
            } else {
                printRecord(stationIdFilter, rec);
            }
        } else {
            for (var e : all.entrySet()) printRecord(e.getKey(), e.getValue());
        }
    }

    private static void printRecord(String id, Map<String,Object> rec) {
        System.out.println("=== " + id + " ===");
        rec.forEach((k,v) -> System.out.println(k + ": " + v));
        System.out.println();
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length()-1) : s;
    }
}
