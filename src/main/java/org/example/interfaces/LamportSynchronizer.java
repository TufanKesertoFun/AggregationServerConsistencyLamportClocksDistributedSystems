package org.example.interfaces;

/**
 * Ensures reads (GETs) observe all writes (PUTs) up to a Lamport timestamp.
 * Usage:
 *  - After applying a PUT:  sync.onPutApplied(lamport);
 *  - Before replying to GET: sync.awaitUpTo(targetLamport, timeoutMs);
 */
public interface LamportSynchronizer {

    /** Record that a PUT with the given Lamport timestamp has been fully applied. */
    public void onPutApplied(long lamportTs);

    /**
     * Block until all applied PUTs cover at least targetLamport, or timeout.
     * @return true if caught up; false if timed out.
     */
    public boolean awaitUpTo(long targetLamport, long timeoutMs);

    /** Latest Lamport timestamp known to be applied. */
    public long lastApplied();
}
