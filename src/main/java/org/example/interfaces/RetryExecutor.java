package org.example.interfaces;

import java.util.concurrent.Callable;

public interface RetryExecutor {
    /**
     * Executes the given operation with retry.
     * @param op  A Callable whose call() may throw Exception. Returns a result or null.
     * @param <T> Result type (use Void for no result)
     * @return the result from the operation
     * @throws Exception if all attempts fail
     */
    <T> T execute(Callable<T> op) throws Exception;
}
