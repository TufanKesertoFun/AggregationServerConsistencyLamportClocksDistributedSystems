package domain.model;

import java.time.Instant;
import java.util.Map;

/** One stored weather reading plus metadata to decide the 'latest'. */
public final class StoredEntry {
    public final Map<String, Object> payload; // JSON map we serve back
    public final String sourceId;             // who sent it (tie-break)
    public final int lamport;                 // server Lamport after onReceive()
    public final Instant updatedAt;           // when we stored it

    public StoredEntry(Map<String, Object> payload, String sourceId, int lamport, Instant updatedAt) {
        this.payload = payload;
        this.sourceId = sourceId;
        this.lamport = lamport;
        this.updatedAt = updatedAt;
    }
}
