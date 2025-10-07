package org.example;

import org.example.interfaces.ExpiryPolicy;
import org.example.util.FixedTtlPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FixedTtlPolicyTest {

    @Test
    void ttlAndExpiry() {
        ExpiryPolicy p = new FixedTtlPolicy(30_000L);
        long now = 1_000_000L;
        assertTrue(p.isExpired(0L, now));                   // no data
        assertFalse(p.isExpired(now - 1000, now));          // fresh
        assertTrue(p.isExpired(now - 31_000, now));         // expired
        assertEquals(30_000L, p.ttlMs());
    }
}
