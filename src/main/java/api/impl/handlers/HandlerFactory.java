package api.impl.handlers;

import api.interfaces.IHandlerFactory;
import api.interfaces.IHttpHandler;
import api.interfaces.http.HttpRequest;
import domain.interfaces.IAggregationService;

public class HandlerFactory implements IHandlerFactory {

    private final IAggregationService service;
    public HandlerFactory(IAggregationService service) { this.service = service; }

    @Override
    public IHttpHandler create(HttpRequest req) {
        String m = req.method().toUpperCase();
        String p = req.path();

        if (!"GET".equals(m) && !"PUT".equals(m)) return new NotFoundHandler(); // 400

        if ("/weather.json".equals(p)) {
            if ("PUT".equals(m)) return new PutHandler(service);
            if ("GET".equals(m)) return new GetHandler(service);
        }
        if ("/health".equals(p) && "GET".equals(m)) return new HealthHandler(service);

        return new NotFoundHandler();
    }
}
