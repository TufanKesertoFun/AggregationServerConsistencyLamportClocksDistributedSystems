package org.example;

import org.example.interfaces.LamportClock;
import org.example.util.AtomicLamportClock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AtomicLamportClockTest {

    @Test
    void tickIncrements() {
        LamportClock c = new AtomicLamportClock();
        assertEquals(0, c.get());
        assertEquals(1, c.tick());
        assertEquals(2, c.tick());
        assertEquals(2, c.get());
    }

    @Test
    void updateMergesAndIncrements() {
        LamportClock c = new AtomicLamportClock();
        c.tick(); // ->1
        long a = c.update(10); // max(1,10)+1 = 11
        assertEquals(11, a);
        assertEquals(11, c.get());
        assertEquals(12, c.tick());
        long b = c.update(5);  // max(12,5)+1 = 13
        assertEquals(13, b);
        assertEquals(13, c.get());
    }
}
