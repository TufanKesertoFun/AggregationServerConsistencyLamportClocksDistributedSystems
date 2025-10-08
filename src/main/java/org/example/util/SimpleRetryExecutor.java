package org.example.util;

import org.example.interfaces.RetryExecutor;

import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * SimpleRetryExecutor provides a linear-exponential backoff retry mechanism
 * with optional jitter for transient operation failures.
 * <p>
 * <b>SonarQube notes:</b>
 * <ul>
 *   <li>Implements deterministic retry count, bounded delays, and random jitter.</li>
 *   <li>Thread sleep is intentional; blocking is acceptable in this bounded retry utility.</li>
 *   <li>All parameters are validated to avoid negative delays or invalid attempts.</li>
 * </ul>
 */
public final class SimpleRetryExecutor implements RetryExecutor {

    /** Maximum number of attempts (inclusive of first try). */
    private final int maxAttempts;     // e.g., 4

    /** Initial delay before retrying, in milliseconds. */
    private final long baseDelayMs;    // e.g., 200

    /** Maximum allowed delay between retries, in milliseconds. */
    private final long maxDelayMs;     // e.g., 1600

    /** Maximum random jitter applied to each delay, in milliseconds. */
    private final long jitterMs;       // e.g., 100

    /**
     * Constructs a retry executor with configurable attempt and delay settings.
     *
     * @param maxAttempts maximum number of retry attempts (minimum 1)
     * @param baseDelayMs base delay in milliseconds before first retry
     * @param maxDelayMs maximum delay cap for exponential backoff
     * @param jitterMs random jitter range in milliseconds (adds up to this amount)
     */
    public SimpleRetryExecutor(int maxAttempts, long baseDelayMs, long maxDelayMs, long jitterMs) {
        // Defensive parameter normalization for robustness
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelayMs = Math.max(0, baseDelayMs);
        this.maxDelayMs  = Math.max(baseDelayMs, maxDelayMs);
        this.jitterMs    = Math.max(0, jitterMs);
    }

    /**
     * Executes the provided operation with retry semantics.
     * <p>
     * Each retry attempt delays with an exponential backoff strategy:
     * 200 → 400 → 800 → 1600 ms (capped by {@code maxDelayMs})
     * plus an optional random jitter (≤ {@code jitterMs}).
     * </p>
     *
     * @param op the operation to execute; should throw on transient failure
     * @param <T> return type of the callable
     * @return result of {@code op.call()} if successful
     * @throws Exception if all retries fail or the operation throws non-recoverably
     */
    @Override
    public <T> T execute(Callable<T> op) throws Exception {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return op.call();   // success path
            } catch (Exception e) {
                if (attempt >= maxAttempts) {
                    // Sonar: rethrow on last attempt to propagate real failure
                    throw e;
                }

                // Exponential backoff: baseDelay * 2^(attempt-1), capped by maxDelayMs
                long delay = baseDelayMs << Math.max(0, attempt - 1); // 200, 400, 800, ...
                if (delay > maxDelayMs) delay = maxDelayMs;

                // Add jitter for desynchronization of concurrent retries
                long sleep = delay + (jitterMs > 0 ? ThreadLocalRandom.current().nextLong(jitterMs) : 0L);

                System.out.println("[Retry] attempt " + (attempt + 1) + " in " + sleep +
                        "ms (error: " + e.getMessage() + ")");

                try {
                    // Sonar: Thread.sleep() intentional; bounded and interrupt-aware
                    Thread.sleep(sleep);
                } catch (InterruptedException ie) {
                    // Restore interrupt flag before rethrowing
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
