package api.interfaces;

import api.interfaces.http.HttpRequest;

public interface IHandlerFactory {
    IHttpHandler create(HttpRequest req);
}
