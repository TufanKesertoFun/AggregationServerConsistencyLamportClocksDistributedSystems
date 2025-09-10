package infrastructure.impl;

import domain.model.StoredEntry;
import domain.interfaces.IStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class LatestReadingStore implements IStore {

    private final ConcurrentHashMap<String, StoredEntry> map = new ConcurrentHashMap<>();

    @Override
    public void upsert(String stationId, Map<String, Object> payload, String sourceId, int lamport) {

        if (stationId == null ){
            return;
        }

        map.compute(stationId, (k, existing) -> {
            Instant now = Instant.now();

            if(existing == null) {
                return new StoredEntry(payload, sourceId, lamport, now);
            }
            if (lamport > existing.lamport){
                return new StoredEntry(payload, sourceId, lamport, now);
            }
            if (lamport < existing.lamport) {
                return existing;
            }

            String exSrc = existing.sourceId == null ? "" : existing.sourceId;
            String newSrc = sourceId == null ? "" : sourceId;
            if (newSrc.compareTo(exSrc) > 0) {
                return new StoredEntry(payload, sourceId, lamport, now);
            }
            return existing;
        });
    }

    @Override
    public Optional<Map<String, Object>> getById(String stationId){
        StoredEntry v = map.get(stationId);
        return Optional.ofNullable(v == null ? null : v.payload);
    }

    @Override
    public Map<String, Map<String, Object>> getAll() {
        Map<String, Map<String, Object>> out = new java.util.HashMap<>();
        map.forEach((id, stored) -> out.put(id, stored.payload));
        return out;
    }

    @Override
    public void expireStale(long nowMillis, long ttlMillis) {
        Instant now = Instant.ofEpochMilli(nowMillis);
        map.entrySet().removeIf(e ->
                Duration.between(e.getValue().updatedAt, now).toMillis() > ttlMillis
        );
    }

    @Override
    public void snapshot() {
        System.out.println("[Store] snapshot size=" + map.size());
    }


}
