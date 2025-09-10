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
import java.util.concurrent.locks.ReentrantLock;

import infrastructure.impl.AggregationServiceImpl;
import domain.interfaces.IStore;
import infrastructure.impl.LatestReadingStore;
import domain.impl.LamportClockImpl;
import infrastructure.util.FilePersistence;
import java.nio.file.Paths;

public class AggregationServer {

    // preserve arrival order: PUT, GET, PUT -> GET runs between them
    private static final ReentrantLock GATE = new ReentrantLock(true);

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 4567;

        String serverId = System.getProperty("SERVER_ID", "srv-1");
        IStore store = new LatestReadingStore();
        LamportClockImpl clock = new LamportClockImpl();
        AggregationServiceImpl service = new AggregationServiceImpl(store, clock, serverId);
        IHandlerFactory factory = new HandlerFactory(service);

        // load previous snapshot if exists
        @SuppressWarnings("unchecked")
        Map<String, Map<String,Object>> loaded =
                FilePersistence.loadOrNull(Paths.get("data/state.json"), Map.class);
        if (loaded != null) {
            for (Map.Entry<String, Map<String,Object>> e : loaded.entrySet()) {
                store.upsert(e.getKey(), e.getValue(), "recovery", 0);
            }
        }

        // 30s expiry sweeper
        final long TTL_MS = 30_000L;
        Thread sweeper = new Thread(() -> {
            try { while (true) { store.expireStale(System.currentTimeMillis(), TTL_MS); Thread.sleep(1000); } }
            catch (InterruptedException ignored) {}
        }, "expiry-sweeper");
        sweeper.setDaemon(true);
        sweeper.start();

        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("[Server] listening on port " + port);

            while (true) {
                try (Socket client = server.accept();
                     InputStream rawIn = client.getInputStream();
                     BufferedInputStream bin = new BufferedInputStream(rawIn);
                     OutputStream out = client.getOutputStream()) {

                    String start = readLineAscii(bin); // e.g., "PUT /weather.json HTTP/1.1"
                    if (start == null || start.isEmpty()) {
                        writePlain(out, 400, "Bad Request", "empty request line");
                        continue;
                    }
                    String[] p = start.split(" ", 3);
                    String method = p.length > 0 ? p[0] : "";
                    String path   = p.length > 1 ? p[1] : "/";
                    String ver    = p.length > 2 ? p[2] : "HTTP/1.1";

                    Map<String,String> headers = new LinkedHashMap<>();
                    String line;
                    while ((line = readLineAscii(bin)) != null && !line.isEmpty()) {
                        int idx = line.indexOf(':');
                        if (idx > 0) {
                            String k = line.substring(0, idx).trim();
                            String v = line.substring(idx + 1).trim();
                            headers.put(k, v);
                            headers.put(k.toLowerCase(), v);
                        }
                    }

                    String expect = headers.get("expect");
                    if (expect != null && expect.equalsIgnoreCase("100-continue")) {
                        OutputStreamWriter w100 = new OutputStreamWriter(out, StandardCharsets.US_ASCII);
                        w100.write("HTTP/1.1 100 Continue\r\n\r\n");
                        w100.flush();
                    }

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
                        GATE.lock();
                        handler.handle(req, res);
                    } catch (Exception e) {
                        res.status(500, "Internal Server Error");
                        res.header("Content-Type", "application/json; charset=utf-8");
                        res.body("{\"error\":\"" + e.getMessage().replace("\"","'") + "\"}");
                    } finally {
                        GATE.unlock();
                    }

                    HttpResponseWriter.write(out, res);

                    // persist after successful PUT
                    if ("PUT".equalsIgnoreCase(method) && "/weather.json".equals(path)
                            && (res.status() == 200 || res.status() == 201)) {
                        try {
                            FilePersistence.saveAtomically(service.getAll(), Paths.get("data"), "state");
                        } catch (IOException ignore) {}
                    }

                } catch (java.net.SocketException se) {
                    String msg = String.valueOf(se.getMessage()).toLowerCase();
                    if (!(msg.contains("connection reset") || msg.contains("broken pipe")
                            || msg.contains("socket write error") || msg.contains("software caused connection abort"))) {
                        System.err.println("[Server] socket error: " + se.getMessage());
                    }
                } catch (Exception e) {
                    System.err.println("[Server] error: " + e.getMessage());
                }
            }
        }
    }

    private static String readLineAscii(BufferedInputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int prev = -1, b;
        while ((b = in.read()) != -1) {
            if (prev == '\r' && b == '\n') {
                byte[] bytes = buf.toByteArray();
                int len = Math.max(0, bytes.length - 1);
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
