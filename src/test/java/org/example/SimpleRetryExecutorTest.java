package org.example;

import org.example.interfaces.RetryExecutor;
import org.example.util.SimpleRetryExecutor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SimpleRetryExecutorTest {

    @Test
    void retriesAndSucceeds() throws Exception {
        RetryExecutor r = new SimpleRetryExecutor(4, 1, 10, 0);
        AtomicInteger n = new AtomicInteger();
        String out = r.execute(() -> {
            if (n.incrementAndGet() < 3) throw new RuntimeException("fail");
            return "ok";
        });
        assertEquals("ok", out);
        assertEquals(3, n.get());
    }

    @Test
    void retriesThenThrows() {
        RetryExecutor r = new SimpleRetryExecutor(3, 1, 10, 0);
        AtomicInteger n = new AtomicInteger();
        assertThrows(Exception.class, () ->
                r.execute(() -> { n.incrementAndGet(); throw new RuntimeException("boom"); })
        );
        assertEquals(3, n.get());
    }
}
