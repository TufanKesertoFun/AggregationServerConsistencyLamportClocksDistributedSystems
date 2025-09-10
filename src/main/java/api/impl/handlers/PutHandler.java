package api.impl.handlers;

import api.interfaces.IHttpHandler;
import api.interfaces.http.HttpRequest;
import api.interfaces.http.HttpResponse;
import com.google.gson.*;
import infrastructure.impl.AggregationServiceImpl;
import domain.interfaces.IAggregationService;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class PutHandler implements IHttpHandler {
    private final IAggregationService service;
    private static final Gson gson = new GsonBuilder().serializeNulls().create();
    private static final long TTL_MS = 30_000;

    public PutHandler(IAggregationService service) { this.service = service; }

    @Override
    public void handle(HttpRequest req, HttpResponse res) {
        // prefer headers for Lamport + Source
        String src = req.header("X-Source-Id");
        if (src == null || src.isBlank()) src = "client-unknown";
        int lam = 0;
        try { String h = req.header("X-Lamport"); if (h != null) lam = Integer.parseInt(h.trim()); }
        catch (NumberFormatException ignored) {}

        byte[] bytes = req.body();
        String payload = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8).trim();

        // 204 No Content if body empty
        if (payload.isEmpty()) {
            res.status(204, "No Content");
            res.header("Content-Type", "application/json; charset=utf-8");
            res.header("X-Lamport", String.valueOf(((AggregationServiceImpl) service).currentLamport()));
            res.body("");
            return;
        }

        try {
            JsonElement root = JsonParser.parseString(payload);
            int applied = 0;
            if (root.isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray()) applied += handleOne(el, src, lam);
            } else {
                applied += handleOne(root, src, lam);
            }

            boolean first = ((AggregationServiceImpl) service)
                    .markContact(src, System.currentTimeMillis(), TTL_MS);

            res.status(first ? 201 : 200, first ? "Created" : "OK");
            res.header("Content-Type", "application/json; charset=utf-8");
            res.header("X-Lamport", String.valueOf(((AggregationServiceImpl) service).currentLamport()));
            res.body("{\"applied\":" + applied + "}");
        } catch (Exception ex) {
            res.status(500, "Internal Server Error");
            res.header("Content-Type", "application/json; charset=utf-8");
            res.header("X-Lamport", String.valueOf(((AggregationServiceImpl) service).currentLamport()));
            res.body("{\"error\":\"invalid json\"}");
        }
    }

    private int handleOne(JsonElement el, String headerSource, int headerLamport) {
        Map<String, Object> m = gson.fromJson(el, Map.class);
        String sourceId = (String) m.getOrDefault("sourceId", headerSource);
        Number lamN = (Number) m.getOrDefault("lamport", headerLamport);
        int lam = lamN == null ? headerLamport : lamN.intValue();
        service.put(sourceId, lam, m);
        return 1;
    }
}
