package org.example.util;

import org.example.interfaces.RetryExecutor;

import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

public final class SimpleRetryExecutor implements RetryExecutor {

    private final int maxAttempts;     // e.g., 4
    private final long baseDelayMs;    // e.g., 200
    private final long maxDelayMs;     // e.g., 1600
    private final long jitterMs;       // e.g., 100

    public SimpleRetryExecutor(int maxAttempts, long baseDelayMs, long maxDelayMs, long jitterMs) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelayMs = Math.max(0, baseDelayMs);
        this.maxDelayMs  = Math.max(baseDelayMs, maxDelayMs);
        this.jitterMs    = Math.max(0, jitterMs);
    }

    @Override
    public <T> T execute(Callable<T> op) throws Exception {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return op.call();   // success
            } catch (Exception e) {
                if (attempt >= maxAttempts) throw e;
                long delay = baseDelayMs << Math.max(0, attempt - 1); // 200, 400, 800...
                if (delay > maxDelayMs) delay = maxDelayMs;
                long sleep = delay + (jitterMs > 0 ? ThreadLocalRandom.current().nextLong(jitterMs) : 0L);
                System.out.println("[Retry] attempt " + (attempt + 1) + " in " + sleep +
                        "ms (error: " + e.getMessage() + ")");
                try { Thread.sleep(sleep); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
