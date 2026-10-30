package com.yizhaoqi.smartpai.client.resilience;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryBudgetTest {

    @Test
    void retriesAreCappedAtTenPercentOfRequests() {
        RetryBudget budget = new RetryBudget(10, 0.1, 0);
        long second = 1_000L;
        for (int i = 0; i < 50; i++) {
            budget.recordRequest(second);
        }
        for (int i = 0; i < 5; i++) {
            assertTrue(budget.tryAcquireRetry(second), "retry " + i);
        }
        assertFalse(budget.tryAcquireRetry(second));
    }

    @Test
    void lowTrafficStillGetsMinimumRetries() {
        RetryBudget budget = new RetryBudget(10, 0.1, 3);
        budget.recordRequest(1_000L);
        assertTrue(budget.tryAcquireRetry(1_000L));
        assertTrue(budget.tryAcquireRetry(1_000L));
        assertTrue(budget.tryAcquireRetry(1_000L));
        assertFalse(budget.tryAcquireRetry(1_000L));
    }

    @Test
    void oldBucketsSlideOutOfTheWindow() {
        RetryBudget budget = new RetryBudget(10, 0.1, 1);
        assertTrue(budget.tryAcquireRetry(1_000L));
        assertFalse(budget.tryAcquireRetry(1_005L));
        // 窗口滑过之后重试额度恢复
        assertTrue(budget.tryAcquireRetry(1_011L));
    }
}
