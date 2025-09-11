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
        // strip http:// or https:// if user passed it
        String hostPort = urlOrHostPort.replaceFirst("^https?://", "");
        String host = parseHost(hostPort);
        int port = parsePort(hostPort, 4567);
        String path = (pathOrNull != null) ? pathOrNull : parsePath(hostPort, "/weather.json");
        if (path != null && !path.startsWith("/")) path = "/" + path;

        try (Socket s = new Socket(host, port);
             OutputStream out = s.getOutputStream();
             InputStream in = s.getInputStream()) {

            String req =
                    "GET " + path + " HTTP/1.1\r\n" +
                            "Host: " + host + ":" + port + "\r\n" +
                            "Connection: close\r\n\r\n";

            System.out.println("Request sent:");
            System.out.print(req.replace("\r\n", "\n"));

            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String resp = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String body = bodyOf(resp);

            return toPrettyAllStrings(body);
        }
    }

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

    private static String toPrettyAllStrings(String jsonBody) {
        Map<String, Object> original = GSON.fromJson(
                jsonBody,
                new TypeToken<LinkedHashMap<String, Object>>(){}.getType()
        );

        LinkedHashMap<String, String> asStrings = new LinkedHashMap<>();

        if (original != null) {
            for (String key : original.keySet()) {
                Object value = original.get(key);
                String valueAsText = (value == null) ? "null" : String.valueOf(value);
                asStrings.put(key, valueAsText);
            }
        }

        return PRETTY.toJson(asStrings);
    }
}
