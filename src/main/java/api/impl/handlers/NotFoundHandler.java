package api.impl.handlers;

import api.interfaces.IHttpHandler;
import api.interfaces.http.HttpRequest;
import api.interfaces.http.HttpResponse;

public class NotFoundHandler implements IHttpHandler {
    @Override
    public void handle(HttpRequest req, HttpResponse res) {
        // assignment simplification: any other method => 400
        res.status(400, "Bad Request");
        res.header("Content-Type", "application/json; charset=utf-8");
        res.body("{\"error\":\"only GET and PUT are supported\"}");
    }
}
