package com.yizhaoqi.smartpai.client.resilience;

import com.yizhaoqi.smartpai.config.RateLimitProperties;
import com.yizhaoqi.smartpai.exception.RateLimitExceededException;
import com.yizhaoqi.smartpai.service.TokenBucketRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 模型调用按供应商 QPS 限流。
 * <p>
 * 令牌桶在 Redis 里，多实例共享一个额度；拿不到令牌的请求在本实例的有界等待队列里等，
 * 队列满了直接拒绝，等到截止时间还拿不到也拒绝。后台任务拿令牌时要给在线请求留出预留余量，
 * 所以桶快空时只有在线请求能拿到，在线问答优先
 */
@Component
public class ModelCallRateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(ModelCallRateLimiter.class);
    private static final long MIN_SLEEP_MS = 20L;

    private final TokenBucketRateLimiter tokenBucketRateLimiter;
    private final RateLimitProperties properties;
    private final Semaphore onlineWaiters;
    private final Semaphore backgroundWaiters;

    public ModelCallRateLimiter(TokenBucketRateLimiter tokenBucketRateLimiter, RateLimitProperties properties) {
        this.tokenBucketRateLimiter = tokenBucketRateLimiter;
        this.properties = properties;
        this.onlineWaiters = new Semaphore(Math.max(properties.getModelQps().getOnlineMaxWaiters(), 1));
        this.backgroundWaiters = new Semaphore(Math.max(properties.getModelQps().getBackgroundMaxWaiters(), 1));
    }

    /**
     * 阻塞直到拿到令牌；不要在 Reactor 的事件循环线程上调用
     *
     * @throws RateLimitExceededException 等待队列已满或超过截止时间
     */
    public void acquire(String scope, String provider, ModelCallPriority priority) {
        RateLimitProperties.ModelQps config = properties.getModelQps();
        double qps = ModelCallGuard.SCOPE_EMBEDDING.equals(scope) ? config.getEmbeddingQps() : config.getLlmQps();
        if (!config.isEnabled() || qps <= 0) {
            return;
        }

        String key = "model:qps:" + scope + ":" + provider;
        double reserve = priority == ModelCallPriority.BACKGROUND ? qps * clampRatio(config.getOnlineReserveRatio()) : 0D;
        if (tryAcquire(key, qps, reserve).allowed()) {
            return;
        }

        boolean online = priority == ModelCallPriority.ONLINE;
        Semaphore waiters = online ? onlineWaiters : backgroundWaiters;
        if (!waiters.tryAcquire()) {
            throw new RateLimitExceededException("模型调用排队已满，请稍后重试", 1L);
        }
        long deadline = System.currentTimeMillis() + (online ? config.getOnlineMaxWaitMs() : config.getBackgroundMaxWaitMs());
        try {
            while (true) {
                TokenBucketRateLimiter.Decision decision = tryAcquire(key, qps, reserve);
                if (decision.allowed()) {
                    return;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0 || decision.waitMillis() > remaining) {
                    throw new RateLimitExceededException("模型调用繁忙，请稍后重试", decision.retryAfterSeconds());
                }
                // 加一点抖动，避免同一批等待者在同一时刻一起醒来抢令牌
                long sleep = Math.max(MIN_SLEEP_MS, decision.waitMillis()) + ThreadLocalRandom.current().nextLong(0, MIN_SLEEP_MS);
                Thread.sleep(Math.min(sleep, remaining));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RateLimitExceededException("模型调用等待被中断", 1L);
        } finally {
            waiters.release();
        }
    }

    private TokenBucketRateLimiter.Decision tryAcquire(String key, double qps, double reserve) {
        try {
            // 容量等于 QPS：最多允许一秒的突发量
            return tokenBucketRateLimiter.tryAcquire(key, qps, qps, reserve);
        } catch (Exception e) {
            // Redis 不可用时放行，限流组件故障不能让模型调用全部失败；熔断和舱壁仍然兜底
            logger.warn("模型 QPS 限流检查失败，本次放行: key={}, error={}", key, e.getMessage());
            return new TokenBucketRateLimiter.Decision(true, 0L);
        }
    }

    private static double clampRatio(double ratio) {
        return Math.min(Math.max(ratio, 0D), 0.9D);
    }
}
