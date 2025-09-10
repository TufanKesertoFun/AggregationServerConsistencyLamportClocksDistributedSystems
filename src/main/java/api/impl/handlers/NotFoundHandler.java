package api.impl.handlers;

import api.interfaces.IHttpHandler;
import api.interfaces.http.HttpRequest;
import api.interfaces.http.HttpResponse;

public class NotFoundHandler implements IHttpHandler {
    @Override
    public void handle(HttpRequest req, HttpResponse res) {
        res.status(404, "Not Found");
        res.header("Content-Type", "application/json; charset=utf-8");
        res.body("{\"error\":\"no route for " + req.method() + " " + req.path() + "\"}");
    }
}
