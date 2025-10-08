package org.example.util;

import org.example.interfaces.LamportClock;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe implementation of a Lamport logical clock using {@link AtomicLong}.
 * <p>
 * <b>SonarQube notes:</b>
 * <ul>
 *   <li>All operations are atomic and non-blocking.</li>
 *   <li>Spin-CAS in {@link #update(long)} is acceptable here because contention is minimal.</li>
 *   <li>No synchronization primitives are required beyond {@code AtomicLong}.</li>
 * </ul>
 */
public final class AtomicLamportClock implements LamportClock {

    // Backing atomic counter holding the Lamport timestamp
    private final AtomicLong time = new AtomicLong(0);

    /**
     * Returns the current Lamport timestamp.
     *
     * @return current clock value
     */
    @Override
    public long get() {
        return time.get();
    }

    /**
     * Increments the clock for a local event (e.g. message send or internal action).
     * <p>Each local event must advance time to preserve causal ordering.</p>
     *
     * @return the incremented timestamp value
     */
    @Override
    public long tick() {
        // Sonar: atomic increment ensures thread safety across concurrent events
        return time.incrementAndGet();
    }

    /**
     * Merges this clock with a remote Lamport timestamp and advances by one tick.
     * <p>
     * The algorithm guarantees monotonic progression:
     * {@code newTime = max(local, remote) + 1}.
     * </p>
     *
     * @param remoteTimestamp timestamp received from another node
     * @return the updated clock value
     */
    @Override
    public long update(long remoteTimestamp) {
        // Merge remote timestamp atomically; CAS loop ensures correctness under concurrency
        while (true) {
            long current = time.get();
            long next = Math.max(current, remoteTimestamp) + 1;
            if (time.compareAndSet(current, next)) {
                return next; // success
            }
            // retry if concurrent update occurred
        }
    }

    /**
     * Returns a human-readable string form of the clock for debugging.
     */
    @Override
    public String toString() {
        return "LamportClock{" + "time=" + time.get() + '}';
    }
}
