package com.yizhaoqi.smartpai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    private final WindowLimit register = new WindowLimit(20, 600);
    private final WindowLimit login = new WindowLimit(30, 60);
    private final WindowLimit chatMessage = new WindowLimit(30, 60);
    private final TokenBudgetLimit llmGlobalToken = new TokenBudgetLimit(120_000L, 60L, 8_000_000L, 86400L);
    private final TokenBudgetLimit embeddingUploadToken = new TokenBudgetLimit(200_000L, 60L, 20_000_000L, 86400L);
    private final DualWindowLimit embeddingQueryRequest = new DualWindowLimit(60L, 60L, 5000L, 86400L);
    private final TokenBudgetLimit embeddingQueryGlobalToken = new TokenBudgetLimit(60_000L, 60L, 4_000_000L, 86400L);
    private final ModelQps modelQps = new ModelQps();

    public WindowLimit getRegister() {
        return register;
    }

    public WindowLimit getLogin() {
        return login;
    }

    public WindowLimit getChatMessage() {
        return chatMessage;
    }

    public TokenBudgetLimit getLlmGlobalToken() {
        return llmGlobalToken;
    }

    public TokenBudgetLimit getEmbeddingUploadToken() {
        return embeddingUploadToken;
    }

    public DualWindowLimit getEmbeddingQueryRequest() {
        return embeddingQueryRequest;
    }

    public TokenBudgetLimit getEmbeddingQueryGlobalToken() {
        return embeddingQueryGlobalToken;
    }

    public ModelQps getModelQps() {
        return modelQps;
    }

    /**
     * 模型调用按供应商的 QPS 令牌桶。超出时请求进有界等待队列，等到截止时间仍拿不到令牌就拒绝；
     * 后台任务（上传向量化、上下文生成、评测）只能用到桶里预留余量以外的令牌，在线问答优先
     */
    public static class ModelQps {
        private boolean enabled = true;
        private double llmQps = 20;
        private double embeddingQps = 20;
        /** 桶里给在线请求预留的比例，后台请求拿完令牌后桶里至少要剩这么多 */
        private double onlineReserveRatio = 0.3;
        private int onlineMaxWaiters = 200;
        private int backgroundMaxWaiters = 50;
        private long onlineMaxWaitMs = 5_000L;
        private long backgroundMaxWaitMs = 60_000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getLlmQps() {
            return llmQps;
        }

        public void setLlmQps(double llmQps) {
            this.llmQps = llmQps;
        }

        public double getEmbeddingQps() {
            return embeddingQps;
        }

        public void setEmbeddingQps(double embeddingQps) {
            this.embeddingQps = embeddingQps;
        }

        public double getOnlineReserveRatio() {
            return onlineReserveRatio;
        }

        public void setOnlineReserveRatio(double onlineReserveRatio) {
            this.onlineReserveRatio = onlineReserveRatio;
        }

        public int getOnlineMaxWaiters() {
            return onlineMaxWaiters;
        }

        public void setOnlineMaxWaiters(int onlineMaxWaiters) {
            this.onlineMaxWaiters = onlineMaxWaiters;
        }

        public int getBackgroundMaxWaiters() {
            return backgroundMaxWaiters;
        }

        public void setBackgroundMaxWaiters(int backgroundMaxWaiters) {
            this.backgroundMaxWaiters = backgroundMaxWaiters;
        }

        public long getOnlineMaxWaitMs() {
            return onlineMaxWaitMs;
        }

        public void setOnlineMaxWaitMs(long onlineMaxWaitMs) {
            this.onlineMaxWaitMs = onlineMaxWaitMs;
        }

        public long getBackgroundMaxWaitMs() {
            return backgroundMaxWaitMs;
        }

        public void setBackgroundMaxWaitMs(long backgroundMaxWaitMs) {
            this.backgroundMaxWaitMs = backgroundMaxWaitMs;
        }
    }

    public static class WindowLimit {
        private int max;
        private long windowSeconds;

        public WindowLimit() {
        }

        public WindowLimit(int max, long windowSeconds) {
            this.max = max;
            this.windowSeconds = windowSeconds;
        }

        public int getMax() {
            return max;
        }

        public void setMax(int max) {
            this.max = max;
        }

        public long getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(long windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }

    public static class DualWindowLimit {
        private long minuteMax;
        private long minuteWindowSeconds;
        private long dayMax;
        private long dayWindowSeconds;

        public DualWindowLimit() {
        }

        public DualWindowLimit(long minuteMax, long minuteWindowSeconds, long dayMax, long dayWindowSeconds) {
            this.minuteMax = minuteMax;
            this.minuteWindowSeconds = minuteWindowSeconds;
            this.dayMax = dayMax;
            this.dayWindowSeconds = dayWindowSeconds;
        }

        public long getMinuteMax() {
            return minuteMax;
        }

        public void setMinuteMax(long minuteMax) {
            this.minuteMax = minuteMax;
        }

        public long getMinuteWindowSeconds() {
            return minuteWindowSeconds;
        }

        public void setMinuteWindowSeconds(long minuteWindowSeconds) {
            this.minuteWindowSeconds = minuteWindowSeconds;
        }

        public long getDayMax() {
            return dayMax;
        }

        public void setDayMax(long dayMax) {
            this.dayMax = dayMax;
        }

        public long getDayWindowSeconds() {
            return dayWindowSeconds;
        }

        public void setDayWindowSeconds(long dayWindowSeconds) {
            this.dayWindowSeconds = dayWindowSeconds;
        }
    }

    public static class TokenBudgetLimit {
        private long minuteMax;
        private long minuteWindowSeconds;
        private long dayMax;
        private long dayWindowSeconds;

        public TokenBudgetLimit() {
        }

        public TokenBudgetLimit(long minuteMax, long minuteWindowSeconds, long dayMax, long dayWindowSeconds) {
            this.minuteMax = minuteMax;
            this.minuteWindowSeconds = minuteWindowSeconds;
            this.dayMax = dayMax;
            this.dayWindowSeconds = dayWindowSeconds;
        }

        public long getMinuteMax() {
            return minuteMax;
        }

        public void setMinuteMax(long minuteMax) {
            this.minuteMax = minuteMax;
        }

        public long getMinuteWindowSeconds() {
            return minuteWindowSeconds;
        }

        public void setMinuteWindowSeconds(long minuteWindowSeconds) {
            this.minuteWindowSeconds = minuteWindowSeconds;
        }

        public long getDayMax() {
            return dayMax;
        }

        public void setDayMax(long dayMax) {
            this.dayMax = dayMax;
        }

        public long getDayWindowSeconds() {
            return dayWindowSeconds;
        }

        public void setDayWindowSeconds(long dayWindowSeconds) {
            this.dayWindowSeconds = dayWindowSeconds;
        }
    }
}
