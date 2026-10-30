package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型调用（LLM / Embedding）的连接池、超时、重试、熔断、舱壁和供应商切换参数
 */
@Data
@Component
@ConfigurationProperties(prefix = "model-call")
public class ModelCallProperties {

    private final Http http = new Http();
    private final Timeout timeout = new Timeout();
    private final RetryPolicy retry = new RetryPolicy();
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();
    private final Bulkhead bulkhead = new Bulkhead();
    private final Failover failover = new Failover();

    @Data
    public static class Http {
        /** 建立 TCP 连接的超时 */
        private int connectTimeoutMs = 5_000;
        /** 两次网络读之间的最大间隔，流式响应在 token 之间的停顿也受它约束 */
        private long readTimeoutSeconds = 60L;
        /** 每个供应商一个连接池 */
        private int maxConnections = 100;
        /** 连接池耗尽时等待空闲连接的最长时间 */
        private long pendingAcquireTimeoutSeconds = 10L;
        /** 空闲连接回收时间，避免拿到已被服务端关闭的连接 */
        private long maxIdleSeconds = 30L;
        /** 响应体内存缓冲上限（MB），embedding 批量返回较大 */
        private int maxInMemorySizeMb = 16;
    }

    @Data
    public static class Timeout {
        /** 流式对话单次尝试的总超时 */
        private long llmStreamSeconds = 180L;
        /** 非流式对话单次尝试的总超时 */
        private long llmBlockingSeconds = 90L;
        /** embedding 单次尝试的总超时 */
        private long embeddingSeconds = 30L;
    }

    @Data
    public static class RetryPolicy {
        /** 首次调用之外最多重试几次；已经收到流式内容后不再重试 */
        private int maxRetries = 2;
        private long initialBackoffMs = 500L;
        private long maxBackoffMs = 8_000L;
        /** 服务端要求的 Retry-After 超过该值时不重试，直接失败或切换供应商 */
        private long maxRetryAfterSeconds = 30L;
        /** 重试预算：滑动窗口内重试次数占请求数的比例上限，超过后停止重试，防止故障时重试放大流量 */
        private double budgetRatio = 0.1;
        /** 低流量时的最少重试额度，避免请求很少时一次重试都做不了 */
        private int budgetMinRetries = 3;
        private int budgetWindowSeconds = 10;
    }

    @Data
    public static class CircuitBreaker {
        private float failureRateThreshold = 50F;
        /** 超过该时长的调用记为慢调用；流式对话整体耗时较长，默认放宽 */
        private long slowCallDurationSeconds = 120L;
        private float slowCallRateThreshold = 80F;
        private int slidingWindowSize = 20;
        private int minimumNumberOfCalls = 10;
        private long waitDurationInOpenSeconds = 30L;
        private int permittedCallsInHalfOpen = 3;
    }

    @Data
    public static class Bulkhead {
        /** 每个 LLM 供应商的最大并发调用数，超出直接拒绝（可切到备用供应商） */
        private int llmMaxConcurrentCalls = 50;
        /** 每个 embedding 供应商的最大并发调用数 */
        private int embeddingMaxConcurrentCalls = 20;
        /** 拿不到并发名额时的等待时间，默认不等 */
        private long maxWaitMs = 0L;
    }

    @Data
    public static class Failover {
        /** 主供应商熔断或失败（尚未输出内容）时是否切到备用 LLM 供应商 */
        private boolean llmEnabled = true;
        /**
         * 备用供应商顺序（provider 编码），为空时按编码顺序使用其余已启用且配置了 API Key 的供应商。
         * embedding 不切换：不同模型的向量不能混用
         */
        private List<String> llmBackupProviders = new ArrayList<>();
    }
}
