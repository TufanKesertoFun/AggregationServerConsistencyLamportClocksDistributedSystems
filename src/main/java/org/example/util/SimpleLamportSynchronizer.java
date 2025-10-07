package org.example.util;

import org.example.interfaces.LamportSynchronizer;

/** Minimal, thread-safe synchronizer for Lamport-ordered GET/PUT consistency. */
public final class SimpleLamportSynchronizer implements LamportSynchronizer {
    private final Object mon = new Object();
    private volatile long lastApplied = 0L;

    @Override
    public void onPutApplied(long lamportTs) {
        synchronized (mon) {
            if (lamportTs > lastApplied) {
                lastApplied = lamportTs;
                mon.notifyAll();
            }
        }
    }

    @Override
    public boolean awaitUpTo(long targetLamport, long timeoutMs) {
        long end = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        synchronized (mon) {
            while (lastApplied < targetLamport) {
                long now = System.currentTimeMillis();
                long wait = end - now;
                if (wait <= 0) return false;
                try { mon.wait(Math.min(10L, wait)); } catch (InterruptedException ignored) {}
            }
            return true;
        }
    }

    @Override
    public long lastApplied() {
        return lastApplied;
    }
}
