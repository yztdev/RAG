package com.yizhaoqi.smartpai.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis 令牌桶。补充、扣减、写回在一个 Lua 脚本里原子完成，时间取 Redis TIME，
 * 不存在 INCR 和 EXPIRE 两条命令之间进程崩溃留下永不过期 key 的问题
 */
@Component
public class TokenBucketRateLimiter {

    private static final String KEY_PREFIX = "ratelimit:tb:";

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>();

    static {
        SCRIPT.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/token_bucket.lua")));
        SCRIPT.setResultType(List.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    public TokenBucketRateLimiter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * @param key            桶标识（会自动加前缀）
     * @param capacity       桶容量，即允许的突发量
     * @param refillPerSecond 每秒补充的令牌数
     * @param reserve        扣减后至少留下的令牌数，给更高优先级的请求预留
     */
    public Decision tryAcquire(String key, double capacity, double refillPerSecond, double reserve) {
        if (capacity <= 0 || refillPerSecond <= 0) {
            return new Decision(false, Long.MAX_VALUE);
        }
        @SuppressWarnings("unchecked")
        List<Long> result = stringRedisTemplate.execute(
                SCRIPT,
                List.of(KEY_PREFIX + key),
                String.valueOf(capacity),
                String.valueOf(refillPerSecond),
                "1",
                String.valueOf(Math.max(reserve, 0D))
        );
        if (result == null || result.size() < 2) {
            // 脚本没有返回结果时放行，限流组件故障不能把正常请求全部拦掉
            return new Decision(true, 0L);
        }
        return new Decision(result.get(0) == 1L, Math.max(result.get(1), 0L));
    }

    /**
     * 按"窗口内最多 max 次"配置换算成令牌桶：容量 max，每秒补充 max / windowSeconds
     */
    public Decision tryAcquireWindow(String key, long max, long windowSeconds) {
        return tryAcquire(key, max, (double) max / Math.max(windowSeconds, 1L), 0D);
    }

    public record Decision(boolean allowed, long waitMillis) {
        public long retryAfterSeconds() {
            return waitMillis == Long.MAX_VALUE ? 60L : Math.max(1L, (waitMillis + 999L) / 1000L);
        }
    }
}
