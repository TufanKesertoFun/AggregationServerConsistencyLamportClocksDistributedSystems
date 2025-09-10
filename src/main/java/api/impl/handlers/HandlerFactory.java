package api.impl.handlers;

import api.interfaces.IHandlerFactory;
import api.interfaces.IHttpHandler;
import api.interfaces.http.HttpRequest;

public class HandlerFactory implements IHandlerFactory {
    @Override
    public IHttpHandler create(HttpRequest req) {
        String m = req.method().toUpperCase();
        String p = req.path();

        if ("/weather.json".equals(p)) {
            if ("PUT".equals(m)) return new PutHandler();
            if ("GET".equals(m)) return new GetHandler();
        }
        return new NotFoundHandler();
    }
}
