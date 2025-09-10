package api.impl.handlers;

import api.interfaces.IHttpHandler;
import api.interfaces.http.HttpRequest;
import api.interfaces.http.HttpResponse;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import infrastructure.impl.AggregationServiceImpl;
import domain.interfaces.IAggregationService;

public class GetHandler implements IHttpHandler {
    private final IAggregationService service;
    private static final Gson gson = new GsonBuilder().serializeNulls().create();

    public GetHandler(IAggregationService service) { this.service = service; }

    @Override
    public void handle(HttpRequest req, HttpResponse res) {
        res.status(200, "OK");
        res.header("Content-Type", "application/json; charset=utf-8");
        res.header("X-Lamport", String.valueOf(((AggregationServiceImpl) service).currentLamport()));
        res.body(gson.toJson(service.getAll()));
    }
}
