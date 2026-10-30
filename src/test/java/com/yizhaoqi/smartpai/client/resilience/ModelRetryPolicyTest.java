package com.yizhaoqi.smartpai.client.resilience;

import com.yizhaoqi.smartpai.config.ModelCallProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRetryPolicyTest {

    private final ModelCallProperties.RetryPolicy config = new ModelCallProperties.RetryPolicy();

    @Test
    void onlyTransientErrorsAreRetryable() {
        assertTrue(ModelRetryPolicy.isRetryable(response(429, null)));
        assertTrue(ModelRetryPolicy.isRetryable(response(503, null)));
        assertTrue(ModelRetryPolicy.isRetryable(new RuntimeException(new TimeoutException())));
        assertFalse(ModelRetryPolicy.isRetryable(response(400, null)));
        assertFalse(ModelRetryPolicy.isRetryable(response(401, null)));
        assertFalse(ModelRetryPolicy.isRetryable(new IllegalStateException("parse")));
    }

    @Test
    void retryAfterHeaderOverridesBackoff() {
        assertEquals(Duration.ofSeconds(2), ModelRetryPolicy.nextDelay(config, 0, response(429, "2")));
        // 服务端要求等太久，不重试
        assertNull(ModelRetryPolicy.nextDelay(config, 0, response(429, "120")));
    }

    @Test
    void exponentialBackoffWithJitterStaysWithinBounds() {
        for (int retry = 0; retry < 10; retry++) {
            long expectedCap = Math.min(config.getMaxBackoffMs(), config.getInitialBackoffMs() << retry);
            long delay = ModelRetryPolicy.nextDelay(config, retry, response(503, null)).toMillis();
            assertTrue(delay >= expectedCap / 2 && delay <= expectedCap, "retry " + retry + " -> " + delay);
        }
    }

    private WebClientResponseException response(int status, String retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) {
            headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return WebClientResponseException.create(status, HttpStatus.valueOf(status).getReasonPhrase(), headers,
                new byte[0], StandardCharsets.UTF_8);
    }
}
