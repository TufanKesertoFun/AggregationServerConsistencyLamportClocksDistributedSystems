package api.impl.handlers;

import api.interfaces.IHttpHandler;
import api.interfaces.http.HttpRequest;
import api.interfaces.http.HttpResponse;

import java.nio.charset.StandardCharsets;

public class PutHandler implements IHttpHandler {

    // Simple in-memory storage shared with GetHandler
    static volatile String lastPayload = null;

    @Override
    public void handle(HttpRequest req, HttpResponse res) {
        byte[] bytes = req.body();
        String payload = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8).trim();

        if (payload.isEmpty()) {
            res.status(400, "Bad Request");
            res.header("Content-Type", "application/json; charset=utf-8");
            res.body("{\"error\":\"empty body\"}");
            return;
        }

        lastPayload = payload;

        res.status(201, "Created");
        res.header("Content-Type", "application/json; charset=utf-8");
        res.body("{\"status\":\"stored\",\"bytes\":" + bytes.length + "}");
    }
}
