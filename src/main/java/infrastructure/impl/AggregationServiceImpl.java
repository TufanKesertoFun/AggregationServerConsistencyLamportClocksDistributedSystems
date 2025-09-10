package infrastructure.impl;

import common.interfaces.ILamportClock;
import domain.interfaces.IAggregationService;
import domain.interfaces.IStore;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class AggregationServiceImpl implements IAggregationService {

    private final IStore store;
    private final ILamportClock clock;
    private final String serverId;

    // track last contact per content server (for 201 vs 200)
    private final ConcurrentHashMap<String, Long> lastSeen = new ConcurrentHashMap<>();

    public AggregationServiceImpl(IStore store, ILamportClock clock, String serverId) {
        this.store = store; this.clock = clock; this.serverId = serverId;
    }

    @Override
    public void put(String sourceId, int lamport, Map<String, Object> data) {
        if (data == null) return;
        Object idObj = data.get("id");
        if (idObj == null) return; // reject if no id
        String stationId = String.valueOf(idObj);

        int serverLamport = clock.onReceive(lamport);
        store.upsert(stationId, data, sourceId, serverLamport);
    }

    @Override public Map<String, Map<String, Object>> getAll() { return store.getAll(); }
    @Override public Optional<Map<String, Object>> getById(String id) { return store.getById(id); }

    public int currentLamport() { return clock.getTime(); }
    public String serverId()    { return serverId; }

    /** @return true if first contact or contact after TTL (so we reply 201) */
    public boolean markContact(String sourceId, long now, long ttlMs) {
        Long prev = lastSeen.put(sourceId, now);
        if (prev == null) return true;
        return (now - prev) > ttlMs;
    }
}
