package com.yizhaoqi.smartpai.client.resilience;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 重试预算：滑动窗口内重试次数不超过请求数的一定比例（默认 10%）。
 * 下游故障时每个请求都重试会把流量放大成好几倍，预算用完就停止重试，失败直接返回或切换供应商。
 * 窗口按秒分桶，桶在新的一秒第一次写入时清零，无锁实现
 */
public class RetryBudget {

    private final int windowSeconds;
    private final double ratio;
    private final int minRetries;
    private final AtomicLongArray bucketSecond;
    private final AtomicLongArray requests;
    private final AtomicLongArray retries;

    public RetryBudget(int windowSeconds, double ratio, int minRetries) {
        this.windowSeconds = Math.max(windowSeconds, 1);
        this.ratio = Math.max(ratio, 0D);
        this.minRetries = Math.max(minRetries, 0);
        this.bucketSecond = new AtomicLongArray(this.windowSeconds);
        this.requests = new AtomicLongArray(this.windowSeconds);
        this.retries = new AtomicLongArray(this.windowSeconds);
    }

    public void recordRequest() {
        recordRequest(nowSecond());
    }

    /**
     * 预算内则记一次重试并返回 true，否则返回 false
     */
    public boolean tryAcquireRetry() {
        return tryAcquireRetry(nowSecond());
    }

    void recordRequest(long second) {
        requests.incrementAndGet(bucketFor(second));
    }

    synchronized boolean tryAcquireRetry(long second) {
        long totalRequests = 0;
        long totalRetries = 0;
        for (int i = 0; i < windowSeconds; i++) {
            if (second - bucketSecond.get(i) < windowSeconds) {
                totalRequests += requests.get(i);
                totalRetries += retries.get(i);
            }
        }
        long allowed = Math.max(minRetries, (long) Math.floor(totalRequests * ratio));
        if (totalRetries >= allowed) {
            return false;
        }
        retries.incrementAndGet(bucketFor(second));
        return true;
    }

    private int bucketFor(long second) {
        int index = (int) (second % windowSeconds);
        long current = bucketSecond.get(index);
        if (current != second && bucketSecond.compareAndSet(index, current, second)) {
            requests.set(index, 0);
            retries.set(index, 0);
        }
        return index;
    }

    private static long nowSecond() {
        return System.currentTimeMillis() / 1000L;
    }
}
