package com.yizhaoqi.smartpai.service.parse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 用信号量限制同时进行的文档解析任务数。
 * 单个文件已经是流式解析，这里防的是多个大文件同时解析把堆撑爆。
 * Kafka 消费、同步重建索引、启动时知识库引导、上传时的 Token 估算都走这一个闸门。
 */
@Component
public class ParseConcurrencyLimiter {

    private static final Logger logger = LoggerFactory.getLogger(ParseConcurrencyLimiter.class);

    private final Semaphore semaphore;
    private final int maxConcurrentTasks;
    private final long permitTimeoutSeconds;

    public ParseConcurrencyLimiter(@Value("${file.parsing.max-concurrent-tasks:2}") int maxConcurrentTasks,
                                   @Value("${file.parsing.permit-timeout-seconds:120}") long permitTimeoutSeconds) {
        if (maxConcurrentTasks <= 0) {
            throw new IllegalArgumentException("file.parsing.max-concurrent-tasks 必须大于 0");
        }
        this.maxConcurrentTasks = maxConcurrentTasks;
        this.permitTimeoutSeconds = permitTimeoutSeconds;
        this.semaphore = new Semaphore(maxConcurrentTasks, true);
    }

    /**
     * 不限并发的实例，仅用于脱离 Spring 容器直接 new ParseService 的单元测试
     */
    public static ParseConcurrencyLimiter unbounded() {
        return new ParseConcurrencyLimiter(Integer.MAX_VALUE, 0);
    }

    /**
     * 获取解析许可，超时抛出 {@link ParseCapacityExceededException}。
     * Kafka 消费场景下异常会触发重试，任务留在队列里而不是挤进 JVM。
     */
    public Permit acquire(String taskName) {
        try {
            if (!semaphore.tryAcquire(permitTimeoutSeconds, TimeUnit.SECONDS)) {
                throw new ParseCapacityExceededException(String.format(
                        "解析并发已满（上限 %d），等待 %d 秒仍未获取许可: %s", maxConcurrentTasks, permitTimeoutSeconds, taskName));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ParseCapacityExceededException("等待解析许可时被中断: " + taskName);
        }
        logger.debug("获取解析许可: task={}, 剩余许可={}", taskName, semaphore.availablePermits());
        return new Permit();
    }

    public int availablePermits() {
        return semaphore.availablePermits();
    }

    public int maxConcurrentTasks() {
        return maxConcurrentTasks;
    }

    public final class Permit implements AutoCloseable {
        private boolean released;

        private Permit() {
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                semaphore.release();
            }
        }
    }

    public static class ParseCapacityExceededException extends RuntimeException {
        public ParseCapacityExceededException(String message) {
            super(message);
        }
    }
}
