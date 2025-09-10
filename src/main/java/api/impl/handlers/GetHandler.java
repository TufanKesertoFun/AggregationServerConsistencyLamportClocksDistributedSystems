package api.impl.handlers;

import api.interfaces.IHttpHandler;
import api.interfaces.http.HttpRequest;
import api.interfaces.http.HttpResponse;

public class GetHandler implements IHttpHandler {
    @Override
    public void handle(HttpRequest req, HttpResponse res) {
        String body = PutHandler.lastPayload;
        if (body == null) {
            res.status(200, "OK");
            res.header("Content-Type", "application/json; charset=utf-8");
            res.body("{}");
            return;
        }
        res.status(200, "OK");
        res.header("Content-Type", "application/json; charset=utf-8");
        // return the stored JSON as-is
        res.body(body);
    }
}
