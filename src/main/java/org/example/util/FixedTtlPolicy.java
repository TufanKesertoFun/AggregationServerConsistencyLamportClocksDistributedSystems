package org.example.util;

import org.example.interfaces.ExpiryPolicy;

/**
 * FixedTtlPolicy implements a constant time-to-live (TTL) expiration strategy.
 * <p>
 * <b>SonarQube notes:</b>
 * <ul>
 *   <li>This class is immutable and thread-safe since {@code ttlMs} is final.</li>
 *   <li>No synchronization is needed because all state is constant.</li>
 *   <li>Used by {@code AggregationServer} to determine if cached data is stale.</li>
 * </ul>
 */
public final class FixedTtlPolicy implements ExpiryPolicy {

    /** Time-to-live duration in milliseconds. */
    private final long ttlMs;

    /**
     * Constructs a fixed TTL policy.
     *
     * @param ttlMs duration in milliseconds before data is considered expired
     */
    public FixedTtlPolicy(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /**
     * Returns the TTL duration in milliseconds.
     *
     * @return TTL in ms
     */
    @Override
    public long ttlMs() {
        return ttlMs;
    }

    /**
     * Determines whether data is expired based on the last applied timestamp.
     * <p>
     * Data is considered expired if:
     * <ul>
     *   <li>{@code lastAppliedAt == 0L} (never updated), or</li>
     *   <li>the elapsed time {@code (now - lastAppliedAt)} exceeds the TTL.</li>
     * </ul>
     *
     * @param lastAppliedAt timestamp of the last data update (ms)
     * @param now current time in milliseconds
     * @return true if expired, false otherwise
     */
    @Override
    public boolean isExpired(long lastAppliedAt, long now) {
        // Sonar: simple arithmetic comparison; overflow not a concern due to realistic time ranges.
        return lastAppliedAt == 0L || (now - lastAppliedAt) > ttlMs;
    }
}
