package com.yizhaoqi.smartpai.client.resilience;

import com.yizhaoqi.smartpai.config.ModelCallProperties;
import com.yizhaoqi.smartpai.exception.RateLimitExceededException;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 给单个供应商的一次模型调用套上保护，从外到内依次是：
 * 熔断器（供应商持续失败时快速失败）→ 舱壁（限制并发）→ 单次尝试总超时 → QPS 令牌桶 → HTTP 调用；
 * 外面再套一层重试（指数退避 + 抖动 + Retry-After + 重试预算，收到内容后不重试）。
 * 熔断器和舱壁按 scope:provider 各一个，一个供应商出问题不会拖垮另一个
 */
@Component
public class ModelCallGuard {

    static final Logger LOGGER = LoggerFactory.getLogger(ModelCallGuard.class);

    public static final String SCOPE_LLM = ModelProviderConfigService.SCOPE_LLM;
    public static final String SCOPE_EMBEDDING = ModelProviderConfigService.SCOPE_EMBEDDING;

    private final ModelCallProperties properties;
    private final ModelCallRateLimiter rateLimiter;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final BulkheadRegistry bulkheadRegistry;
    private final Map<String, RetryBudget> retryBudgets = new ConcurrentHashMap<>();

    public ModelCallGuard(ModelCallProperties properties, ModelCallRateLimiter rateLimiter) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig(properties.getCircuitBreaker()));
        this.bulkheadRegistry = BulkheadRegistry.ofDefaults();
        this.circuitBreakerRegistry.getEventPublisher().onEntryAdded(event -> event.getAddedEntry().getEventPublisher()
                .onStateTransition(transition -> LOGGER.warn("模型供应商熔断器状态变化: name={}, transition={}",
                        transition.getCircuitBreakerName(), transition.getStateTransition())));
    }

    /**
     * 流式调用。contentReceived 在收到第一条数据时置为 true，之后失败不再重试，也不应切换供应商
     */
    public <T> Flux<T> guardFlux(String scope,
                                 String provider,
                                 ModelCallPriority priority,
                                 Duration attemptTimeout,
                                 Supplier<Flux<T>> call,
                                 AtomicBoolean contentReceived) {
        String name = scope + ":" + provider;
        RetryBudget budget = retryBudget(name);
        Flux<T> attempt = Mono.fromRunnable(() -> rateLimiter.acquire(scope, provider, priority))
                .subscribeOn(Schedulers.boundedElastic())
                .thenMany(Flux.defer(call))
                .transform(withDeadline(attemptTimeout))
                .transformDeferred(BulkheadOperator.of(bulkhead(scope, name)))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker(name)))
                .doOnNext(item -> contentReceived.set(true));
        return Flux.defer(() -> {
            budget.recordRequest();
            return attempt.retryWhen(ModelRetryPolicy.retrySpec(properties.getRetry(), budget, contentReceived::get, name));
        });
    }

    /**
     * 非流式调用（一次请求一次响应）
     */
    public <T> Mono<T> guardMono(String scope,
                                 String provider,
                                 ModelCallPriority priority,
                                 Duration attemptTimeout,
                                 Supplier<Mono<T>> call) {
        return guardFlux(scope, provider, priority, attemptTimeout, () -> call.get().flux(), new AtomicBoolean(false))
                .singleOrEmpty();
    }

    public CircuitBreaker.State circuitState(String scope, String provider) {
        return circuitBreaker(scope + ":" + provider).getState();
    }

    private CircuitBreaker circuitBreaker(String name) {
        return circuitBreakerRegistry.circuitBreaker(name);
    }

    private Bulkhead bulkhead(String scope, String name) {
        ModelCallProperties.Bulkhead config = properties.getBulkhead();
        int maxConcurrent = SCOPE_EMBEDDING.equals(scope) ? config.getEmbeddingMaxConcurrentCalls() : config.getLlmMaxConcurrentCalls();
        return bulkheadRegistry.bulkhead(name, () -> BulkheadConfig.custom()
                .maxConcurrentCalls(Math.max(maxConcurrent, 1))
                .maxWaitDuration(Duration.ofMillis(Math.max(config.getMaxWaitMs(), 0L)))
                .build());
    }

    private RetryBudget retryBudget(String name) {
        ModelCallProperties.RetryPolicy config = properties.getRetry();
        return retryBudgets.computeIfAbsent(name, ignored ->
                new RetryBudget(config.getBudgetWindowSeconds(), config.getBudgetRatio(), config.getBudgetMinRetries()));
    }

    /**
     * 单次尝试的总超时：从订阅开始计时，覆盖首包等待和整个流式输出，不是两条数据之间的间隔
     */
    private static <T> Function<Flux<T>, Flux<T>> withDeadline(Duration timeout) {
        return flux -> Flux.defer(() -> {
            long deadline = System.nanoTime() + timeout.toNanos();
            return flux.timeout(Mono.delay(timeout),
                    item -> Mono.delay(Duration.ofNanos(Math.max(0L, deadline - System.nanoTime()))));
        });
    }

    private static CircuitBreakerConfig circuitBreakerConfig(ModelCallProperties.CircuitBreaker config) {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(config.getFailureRateThreshold())
                .slowCallDurationThreshold(Duration.ofSeconds(Math.max(config.getSlowCallDurationSeconds(), 1L)))
                .slowCallRateThreshold(config.getSlowCallRateThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(Math.max(config.getSlidingWindowSize(), 1))
                .minimumNumberOfCalls(Math.max(config.getMinimumNumberOfCalls(), 1))
                .waitDurationInOpenState(Duration.ofSeconds(Math.max(config.getWaitDurationInOpenSeconds(), 1L)))
                .permittedNumberOfCallsInHalfOpenState(Math.max(config.getPermittedCallsInHalfOpen(), 1))
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(ModelRetryPolicy::isProviderFailure)
                .ignoreException(error -> error instanceof RateLimitExceededException)
                .build();
    }
}
