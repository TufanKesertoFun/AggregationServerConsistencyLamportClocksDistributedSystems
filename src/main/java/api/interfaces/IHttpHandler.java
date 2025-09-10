package api.interfaces;

import api.interfaces.http.HttpRequest;
import api.interfaces.http.HttpResponse;

public interface IHttpHandler {
    void handle(HttpRequest req, HttpResponse res) throws Exception;
}
