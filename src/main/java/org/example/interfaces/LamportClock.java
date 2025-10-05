package org.example.interfaces;

/**
 * A simple contract for Lamport logical clocks.
 * Provides atomic, thread-safe operations for incrementing
 * and updating clocks based on received timestamps.
 */
public interface LamportClock {

    /**
     * Returns the current logical clock value.
     */
    long get();

    /**
     * Increments the clock for a local event.
     * @return the updated clock value
     */
    long tick();

    /**
     * Updates the clock when receiving a message from another process.
     * @param remoteTimestamp the Lamport timestamp from the sender
     * @return the updated clock value
     */
    long update(long remoteTimestamp);
}
