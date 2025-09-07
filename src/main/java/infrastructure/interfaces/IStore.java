package infrastructure.interfaces;

import java.util.Map;
import java.util.Optional;

public interface IStore {
    void upsert(String stationId, Map<String,Object> payload, String sourceId, int lamport);
    Optional<Map<String,Object>> getById(String stationId);
    Map<String, Map<String,Object>> getAll();
    void expireStale(long nowMillis, long ttlMillis);
    void snapshot();
}