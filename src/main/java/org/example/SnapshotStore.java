package org.example;

public interface SnapshotStore {

    /** Load the most recent JSON snapshot, or null if none. */
    String load();
    /** Save the given JSON snapshot. Must be safe to call repeatedly. */
    void save(String json);
}
