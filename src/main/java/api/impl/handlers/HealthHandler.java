package api.impl.handlers;

import api.interfaces.IHttpHandler;
import api.interfaces.http.HttpRequest;
import api.interfaces.http.HttpResponse;
import infrastructure.impl.AggregationServiceImpl;
import domain.interfaces.IAggregationService;

public class HealthHandler implements IHttpHandler {
    private final AggregationServiceImpl svc;
    public HealthHandler(IAggregationService service) { this.svc = (AggregationServiceImpl) service; }

    @Override
    public void handle(HttpRequest req, HttpResponse res) {
        res.status(200, "OK");
        res.header("Content-Type", "application/json; charset=utf-8");
        res.header("X-Lamport", String.valueOf(svc.currentLamport()));
        res.body("{\"status\":\"ok\",\"serverId\":\"" + svc.serverId() + "\",\"lamport\":" + svc.currentLamport() + "}");
    }
}
