package com.yizhaoqi.smartpai.utils;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestDeadlineTest {

    @Test
    void withoutDeadlineConfiguredTimeoutIsUnchanged() {
        assertTrue(RequestDeadline.remaining().isEmpty());
        assertEquals(Duration.ofSeconds(10), RequestDeadline.cap(Duration.ofSeconds(10)));
        assertTrue(RequestDeadline.hasAtLeast(Duration.ofDays(1)));
    }

    @Test
    void capTakesTheSmallerOfConfigAndRemaining() {
        try (RequestDeadline.Scope ignored = RequestDeadline.open(RequestDeadline.after(Duration.ofSeconds(3)))) {
            assertTrue(RequestDeadline.cap(Duration.ofSeconds(10)).compareTo(Duration.ofSeconds(3)) <= 0);
            assertEquals(Duration.ofSeconds(1), RequestDeadline.cap(Duration.ofSeconds(1)));
            assertTrue(RequestDeadline.hasAtLeast(Duration.ofSeconds(2)));
            assertFalse(RequestDeadline.hasAtLeast(Duration.ofSeconds(5)));
        }
        assertTrue(RequestDeadline.remaining().isEmpty());
    }

    @Test
    void nestedScopeCanOnlyShortenAndRestoresOuterOnClose() {
        long outer = RequestDeadline.after(Duration.ofSeconds(60));
        try (RequestDeadline.Scope ignoredOuter = RequestDeadline.open(outer)) {
            try (RequestDeadline.Scope ignoredInner = RequestDeadline.open(outer - Duration.ofSeconds(30).toNanos())) {
                assertFalse(RequestDeadline.hasAtLeast(Duration.ofSeconds(31)));
            }
            // 内层想放宽也不行
            try (RequestDeadline.Scope ignoredLater = RequestDeadline.open(outer + Duration.ofSeconds(60).toNanos())) {
                assertEquals(outer, RequestDeadline.currentNanos().orElseThrow());
            }
            assertEquals(outer, RequestDeadline.currentNanos().orElseThrow());
        }
    }

    @Test
    void expiredDeadlineReportsZeroRemaining() {
        try (RequestDeadline.Scope ignored = RequestDeadline.open(System.nanoTime() - 1)) {
            assertEquals(Duration.ZERO, RequestDeadline.remaining().orElseThrow());
            assertEquals(Duration.ZERO, RequestDeadline.cap(Duration.ofSeconds(5)));
        }
    }
}
