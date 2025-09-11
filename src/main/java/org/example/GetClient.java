package org.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GetClient {
    private static final Gson GSON = new Gson();
    private static final Gson PRETTY = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: GETClient <http://host:port[/path]> OR <host:port> [path]");
            System.out.println("Example: GETClient localhost:4567 /weather.json");
            return;
        }

        String hostPort = args[0].replace("http://", "");
        String host = parseHost(hostPort);
        int port = parsePort(hostPort, 4567);
        String path = (args.length >= 2) ? args[1] : parsePath(hostPort, "/weather.json"); // default path

        try (Socket s = new Socket(host, port);
             OutputStream out = s.getOutputStream();
             InputStream in = s.getInputStream()) {

            // ---- build and show the request ----
            String req =
                    "GET " + path + " HTTP/1.1\r\n" +
                            "Host: " + host + ":" + port + "\r\n" +
                            "Connection: close\r\n\r\n";
            System.out.println("Request sent:");
            System.out.print(req.replace("\r\n", "\n"));

            // ---- send ----
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();

            // ---- read full response ----
            String resp = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String body = bodyOf(resp);

            // ---- print pretty JSON with ALL values quoted ----
            System.out.println("\nServer Response:");
            System.out.println(toPrettyAllStrings(body));
        }
    }

    /* ---------------- helpers ---------------- */

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

    /** Convert JSON body to pretty JSON where every value is a string (so numbers show in quotes). */
    private static String toPrettyAllStrings(String jsonBody) {
        // preserve key order by reading into LinkedHashMap
        Map<String, Object> original =
                GSON.fromJson(jsonBody, new TypeToken<LinkedHashMap<String, Object>>(){}.getType());
        LinkedHashMap<String, String> asStrings = new LinkedHashMap<>();
        if (original != null) {
            for (Map.Entry<String, Object> e : original.entrySet()) {
                asStrings.put(e.getKey(), e.getValue() == null ? "null" : String.valueOf(e.getValue()));
            }
        }
        return PRETTY.toJson(asStrings);
    }
}
