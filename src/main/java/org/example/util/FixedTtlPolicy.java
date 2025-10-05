package org.example.util;

import org.example.interfaces.ExpiryPolicy;

public final class FixedTtlPolicy implements ExpiryPolicy {
    private final long ttlMs;


    public FixedTtlPolicy(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    @Override
    public long ttlMs(){
        return ttlMs;
    }

    @Override
    public boolean isExpired(long lastAppliedAt, long now){
        return lastAppliedAt == 0L || (now - lastAppliedAt) > ttlMs;
    }
}
