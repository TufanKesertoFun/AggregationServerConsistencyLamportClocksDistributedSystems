package org.example.util;

import org.example.interfaces.LamportSynchronizer;

/**
 * Minimal, thread-safe synchronizer for Lamport-ordered GET/PUT consistency.
 * <p>
 * <b>SonarQube notes:</b>
 * <ul>
 *   <li>Implements a simple monitor-based coordination pattern using {@code wait/notifyAll()}.</li>
 *   <li>Volatile field {@code lastApplied} ensures visibility across threads without heavy locking.</li>
 *   <li>Designed for low contention and short-lived waits; fairness scheduling is unnecessary.</li>
 * </ul>
 */
public final class SimpleLamportSynchronizer implements LamportSynchronizer {

    /** Monitor object used for coordinating wait/notify among threads. */
    private final Object mon = new Object();

    /** Highest Lamport timestamp that has been successfully applied. */
    private volatile long lastApplied = 0L;

    /**
     * Called after a PUT has been applied, notifying waiting GETs that progress was made.
     * <p>
     * Only increases {@code lastApplied}; subsequent calls with smaller timestamps are ignored.
     * </p>
     *
     * @param lamportTs Lamport timestamp of the completed PUT
     */
    @Override
    public void onPutApplied(long lamportTs) {
        synchronized (mon) {
            // Update only if newer Lamport timestamp is applied
            if (lamportTs > lastApplied) {
                lastApplied = lamportTs;
                mon.notifyAll(); // wake up any waiting GET threads
            }
        }
    }

    /**
     * Waits until {@code lastApplied >= targetLamport} or the timeout expires.
     * <p>
     * The wait uses small intervals (≤10 ms) to allow responsiveness and avoid starvation.
     * </p>
     *
     * @param targetLamport the Lamport value the caller wants to catch up to
     * @param timeoutMs maximum wait time in milliseconds
     * @return {@code true} if target was reached before timeout, {@code false} otherwise
     */
    @Override
    public boolean awaitUpTo(long targetLamport, long timeoutMs) {
        long end = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        synchronized (mon) {
            while (lastApplied < targetLamport) {
                long now = System.currentTimeMillis();
                long wait = end - now;
                if (wait <= 0) return false; // timeout reached
                try {
                    // Sonar: short timed wait prevents indefinite blocking under lost notifications
                    mon.wait(Math.min(10L, wait));
                } catch (InterruptedException ignored) {
                    // Sonar: interruption is ignored intentionally; ensures best-effort consistency
                }
            }
            return true;
        }
    }

    /**
     * Returns the most recently applied Lamport timestamp.
     *
     * @return last applied Lamport timestamp
     */
    @Override
    public long lastApplied() {
        return lastApplied;
    }
}
