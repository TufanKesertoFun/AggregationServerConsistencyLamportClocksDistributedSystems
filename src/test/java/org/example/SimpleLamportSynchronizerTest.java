package org.example;

import org.example.util.SimpleLamportSynchronizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimpleLamportSynchronizerTest {

    @Test
    void awaitUpToSignals() {
        SimpleLamportSynchronizer s = new SimpleLamportSynchronizer();

        Thread t = new Thread(() -> {
            try { Thread.sleep(25); } catch (InterruptedException ignored) {}
            s.onPutApplied(5L);
        });
        t.start();

        assertTrue(s.awaitUpTo(5L, 1000L));
        assertEquals(5L, s.lastApplied());
    }

    @Test
    void awaitTimesOut() {
        SimpleLamportSynchronizer s = new SimpleLamportSynchronizer();
        assertFalse(s.awaitUpTo(1L, 20L));
        assertEquals(0L, s.lastApplied());
    }
}
