package com.yizhaoqi.smartpai.client.resilience;

import com.yizhaoqi.smartpai.config.ModelCallProperties;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * 模型调用的重试策略，只放在客户端这一层：
 * 只重试网络错误、超时、429 和 5xx；指数退避加抖动；429/503 带 Retry-After 时按服务端要求等待；
 * 已经收到流式内容后不重试；超过重试预算不重试
 */
public final class ModelRetryPolicy {

    private ModelRetryPolicy() {
    }

    public static Retry retrySpec(ModelCallProperties.RetryPolicy config,
                                  RetryBudget budget,
                                  BooleanSupplier contentReceived,
                                  String callName) {
        return Retry.from(signals -> signals.concatMap(signal -> {
            Throwable failure = signal.failure();
            if (contentReceived.getAsBoolean()
                    || signal.totalRetries() >= config.getMaxRetries()
                    || !isRetryable(failure)) {
                return Mono.error(failure);
            }
            Duration delay = nextDelay(config, signal.totalRetries(), failure);
            if (delay == null) {
                return Mono.error(failure);
            }
            if (!budget.tryAcquireRetry()) {
                ModelCallGuard.LOGGER.warn("重试预算已用尽，不再重试: call={}, error={}", callName, failure.toString());
                return Mono.error(failure);
            }
            ModelCallGuard.LOGGER.warn("模型调用失败，{}ms 后第 {} 次重试: call={}, error={}",
                    delay.toMillis(), signal.totalRetries() + 1, callName, failure.toString());
            return Mono.delay(delay);
        }));
    }

    /**
     * 可以重试的错误：连接失败、读超时、总超时、429、408 和 5xx
     */
    public static boolean isRetryable(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            return status == 429 || status == 408 || status >= 500;
        }
        return cause instanceof WebClientRequestException || cause instanceof TimeoutException;
    }

    /**
     * 熔断器记为失败的错误：说明供应商本身不健康，和可重试错误一致；
     * 400 这类请求参数问题、我们自己的限流和舱壁拒绝不计入
     */
    public static boolean isProviderFailure(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof CallNotPermittedException || cause instanceof BulkheadFullException) {
            return false;
        }
        return isRetryable(cause);
    }

    /**
     * 第 retriesSoFar+1 次重试前的等待时间；返回 null 表示服务端要求等太久，不重试
     */
    static Duration nextDelay(ModelCallProperties.RetryPolicy config, long retriesSoFar, Throwable error) {
        Duration retryAfter = retryAfter(error);
        if (retryAfter != null) {
            return retryAfter.getSeconds() > config.getMaxRetryAfterSeconds() ? null : retryAfter;
        }
        long base = Math.max(config.getInitialBackoffMs(), 1L);
        long cap = Math.max(config.getMaxBackoffMs(), base);
        long exponential = Math.min(cap, base << Math.min(retriesSoFar, 20));
        // 全抖动：在 [exponential/2, exponential] 之间随机，避免大量请求同一时刻重试
        long jittered = exponential / 2 + ThreadLocalRandom.current().nextLong(0, exponential / 2 + 1);
        return Duration.ofMillis(jittered);
    }

    /**
     * 解析 Retry-After：秒数或 HTTP 日期
     */
    static Duration retryAfter(Throwable error) {
        if (!(unwrap(error) instanceof WebClientResponseException responseException)) {
            return null;
        }
        String value = responseException.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(Long.parseLong(value.trim()), 0L));
        } catch (NumberFormatException ignored) {
            // 不是秒数时按 HTTP 日期解析
        }
        try {
            ZonedDateTime at = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
            Duration until = Duration.between(ZonedDateTime.now(at.getZone()), at);
            return until.isNegative() ? Duration.ZERO : until;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null
                && !(current instanceof WebClientResponseException)
                && !(current instanceof WebClientRequestException)
                && !(current instanceof TimeoutException)
                && !(current instanceof CallNotPermittedException)
                && !(current instanceof BulkheadFullException)) {
            current = current.getCause();
        }
        return current;
    }
}
