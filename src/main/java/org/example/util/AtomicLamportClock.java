package org.example.util;

import org.example.interfaces.LamportClock;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe implementation of Lamport logical clock.
 */
public final class AtomicLamportClock implements LamportClock {

    private final AtomicLong time = new AtomicLong(0);

    @Override
    public long get() {
        return time.get();
    }

    @Override
    public long tick() {
        // increment for local events (PUT, GET, etc.)
        return time.incrementAndGet();
    }

    @Override
    public long update(long remoteTimestamp) {
        // merge with remote Lamport timestamp
        while (true) {
            long current = time.get();
            long next = Math.max(current, remoteTimestamp) + 1;
            if (time.compareAndSet(current, next))
                return next;
        }
    }

    @Override
    public String toString() {
        return "LamportClock{" + "time=" + time.get() + '}';
    }
}
