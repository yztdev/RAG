package com.yizhaoqi.smartpai.service.parse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParseConcurrencyLimiterTest {

    @Test
    void limitsConcurrentParseTasksAndReleasesOnClose() {
        ParseConcurrencyLimiter limiter = new ParseConcurrencyLimiter(2, 0);

        ParseConcurrencyLimiter.Permit first = limiter.acquire("a");
        ParseConcurrencyLimiter.Permit second = limiter.acquire("b");
        assertEquals(0, limiter.availablePermits());
        assertThrows(ParseConcurrencyLimiter.ParseCapacityExceededException.class, () -> limiter.acquire("c"));

        first.close();
        first.close();
        assertEquals(1, limiter.availablePermits(), "重复 close 不会多释放许可");
        try (ParseConcurrencyLimiter.Permit ignored = limiter.acquire("c")) {
            assertEquals(0, limiter.availablePermits());
        }
        second.close();
        assertEquals(2, limiter.availablePermits());
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> new ParseConcurrencyLimiter(0, 1));
    }
}
