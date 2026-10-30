package com.yizhaoqi.smartpai.client.resilience;

import com.yizhaoqi.smartpai.config.ModelCallProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ModelCallGuardTest {

    private ModelCallProperties properties;
    private ModelCallGuard guard;

    @BeforeEach
    void setUp() {
        properties = new ModelCallProperties();
        properties.getRetry().setInitialBackoffMs(1);
        properties.getRetry().setMaxBackoffMs(2);
        guard = new ModelCallGuard(properties, mock(ModelCallRateLimiter.class));
    }

    @Test
    void transientFailureIsRetriedUntilSuccess() {
        AtomicInteger calls = new AtomicInteger();
        String result = guard.guardMono("llm", "p1", ModelCallPriority.ONLINE, Duration.ofSeconds(5), () -> Mono.defer(() ->
                calls.incrementAndGet() < 3 ? Mono.error(status(503)) : Mono.just("ok"))).block();

        assertEquals("ok", result);
        assertEquals(3, calls.get());
    }

    @Test
    void clientErrorIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(WebClientResponseException.class, () -> guard.guardMono("llm", "p2", ModelCallPriority.ONLINE,
                Duration.ofSeconds(5), () -> Mono.defer(() -> {
                    calls.incrementAndGet();
                    return Mono.<String>error(status(400));
                })).block());
        assertEquals(1, calls.get());
    }

    @Test
    void streamIsNotRetriedOnceContentWasReceived() {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean received = new AtomicBoolean(false);
        Flux<String> flux = guard.guardFlux("llm", "p3", ModelCallPriority.ONLINE, Duration.ofSeconds(5),
                () -> Flux.defer(() -> {
                    calls.incrementAndGet();
                    return Flux.concat(Flux.just("hello"), Flux.error(status(503)));
                }), received);

        assertThrows(WebClientResponseException.class, () -> flux.collectList().block());
        assertEquals(1, calls.get());
    }

    @Test
    void deadlineCoversTheWholeStreamNotJustTheGapBetweenItems() {
        Flux<Long> slowStream = Flux.interval(Duration.ofMillis(40)).take(50);
        properties.getRetry().setMaxRetries(0);
        AtomicBoolean received = new AtomicBoolean(false);

        RuntimeException error = assertThrows(RuntimeException.class, () -> guard.guardFlux("llm", "p4",
                ModelCallPriority.ONLINE, Duration.ofMillis(300), () -> slowStream, received).collectList().block());
        assertInstanceOf(java.util.concurrent.TimeoutException.class, error.getCause() == null ? error : error.getCause());
    }

    @Test
    void breakerOpensAfterRepeatedProviderFailures() {
        properties.getRetry().setMaxRetries(0);
        properties.getCircuitBreaker().setMinimumNumberOfCalls(2);
        properties.getCircuitBreaker().setSlidingWindowSize(2);
        guard = new ModelCallGuard(properties, mock(ModelCallRateLimiter.class));
        for (int i = 0; i < 2; i++) {
            assertThrows(Exception.class, () -> guard.guardMono("llm", "p5", ModelCallPriority.ONLINE, Duration.ofSeconds(5),
                    () -> Mono.<String>error(status(500))).block());
        }
        assertEquals(CircuitBreaker.State.OPEN, guard.circuitState("llm", "p5"));

        AtomicInteger calls = new AtomicInteger();
        assertThrows(CallNotPermittedException.class, () -> guard.guardMono("llm", "p5", ModelCallPriority.ONLINE,
                Duration.ofSeconds(5), () -> Mono.fromCallable(() -> {
                    calls.incrementAndGet();
                    return "never";
                })).block());
        assertEquals(0, calls.get());
        // 另一个供应商不受影响
        assertEquals(List.of("ok"), guard.guardFlux("llm", "p6", ModelCallPriority.ONLINE, Duration.ofSeconds(5),
                () -> Flux.just("ok"), new AtomicBoolean()).collectList().block());
    }

    private WebClientResponseException status(int code) {
        return WebClientResponseException.create(code, "status " + code, new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
    }
}
