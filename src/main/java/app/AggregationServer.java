package app;

import api.impl.handlers.HandlerFactory;
import api.impl.MinimalHttpRequest;
import api.impl.HttpResponseImpl;
import api.impl.HttpResponseWriter;
import api.interfaces.IHandlerFactory;
import api.interfaces.IHttpHandler;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class AggregationServer {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 4567;
        IHandlerFactory factory = new HandlerFactory();

        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("[Server] listening on port " + port);

            while (true) {
                try (Socket client = server.accept();
                     InputStream rawIn = client.getInputStream();
                     BufferedInputStream bin = new BufferedInputStream(rawIn);
                     OutputStream out = client.getOutputStream()) {

                    // ---- request line
                    String start = readLineAscii(bin); // e.g. "PUT /weather.json HTTP/1.1"
                    if (start == null || start.isEmpty()) {
                        writePlain(out, 400, "Bad Request", "empty request line");
                        continue;
                    }
                    String[] p = start.split(" ", 3);
                    String method = p.length > 0 ? p[0] : "";
                    String path   = p.length > 1 ? p[1] : "/";
                    String ver    = p.length > 2 ? p[2] : "HTTP/1.1";

                    // ---- headers
                    Map<String,String> headers = new LinkedHashMap<>();
                    String line;
                    while ((line = readLineAscii(bin)) != null && !line.isEmpty()) {
                        int idx = line.indexOf(':');
                        if (idx > 0) {
                            String k = line.substring(0, idx).trim();
                            String v = line.substring(idx + 1).trim();
                            headers.put(k, v);
                            headers.put(k.toLowerCase(), v); // allow case-insensitive lookup
                        }
                    }

                    // ---- Expect: 100-continue
                    String expect = headers.get("expect");
                    if (expect != null && expect.equalsIgnoreCase("100-continue")) {
                        OutputStreamWriter w100 = new OutputStreamWriter(out, StandardCharsets.US_ASCII);
                        w100.write("HTTP/1.1 100 Continue\r\n\r\n");
                        w100.flush();
                    }

                    // ---- body (Content-Length only)
                    int len = 0;
                    try { len = Integer.parseInt(headers.getOrDefault("content-length", "0")); }
                    catch (NumberFormatException ignored) {}
                    byte[] body = new byte[len];
                    int total = 0;
                    while (total < len) {
                        int n = bin.read(body, total, len - total);
                        if (n < 0) break;
                        total += n;
                    }

                    MinimalHttpRequest req = new MinimalHttpRequest(method, path, ver, headers, body);
                    HttpResponseImpl res   = new HttpResponseImpl();

                    IHttpHandler handler = factory.create(req);
                    try {
                        handler.handle(req, res);
                    } catch (Exception e) {
                        res.status(500, "Internal Server Error");
                        res.header("Content-Type", "application/json; charset=utf-8");
                        res.body("{\"error\":\"" + e.getMessage().replace("\"","'") + "\"}");
                    }

                    HttpResponseWriter.write(out, res);

                } catch (java.net.SocketException se) {
                    String msg = String.valueOf(se.getMessage()).toLowerCase();
                    if (!(msg.contains("connection reset")
                            || msg.contains("broken pipe")
                            || msg.contains("socket write error")
                            || msg.contains("software caused connection abort"))) {
                        System.err.println("[Server] socket error: " + se.getMessage());
                    }
                } catch (Exception e) {
                    System.err.println("[Server] error: " + e.getMessage());
                }
            }
        }
    }

    // Read an ASCII line terminated by CRLF, return without CRLF; null on EOF
    private static String readLineAscii(BufferedInputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int prev = -1, b;
        while ((b = in.read()) != -1) {
            if (prev == '\r' && b == '\n') {
                byte[] bytes = buf.toByteArray();
                int len = Math.max(0, bytes.length - 1); // drop trailing \r
                return new String(bytes, 0, len, StandardCharsets.US_ASCII);
            }
            buf.write(b);
            prev = b;
        }
        return (buf.size() == 0) ? null : new String(buf.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static void writePlain(OutputStream out, int code, String reason, String body) throws IOException {
        api.impl.HttpResponseImpl res = new api.impl.HttpResponseImpl();
        res.status(code, reason);
        res.header("Content-Type", "text/plain; charset=utf-8");
        res.body(body == null ? "" : body);
        api.impl.HttpResponseWriter.write(out, res);
    }
}
