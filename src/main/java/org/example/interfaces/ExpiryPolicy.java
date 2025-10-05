package org.example.interfaces;

public interface ExpiryPolicy {

    long ttlMs();
    default boolean isExpired(long lastAppliedAt, long now){
        return lastAppliedAt == 0L || (now - lastAppliedAt) > ttlMs();
    }
}
