package com.yizhaoqi.smartpai.consumer;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * 堆内存背压：堆使用率超过阈值时暂停 Kafka 文件处理消费，降到恢复阈值以下再恢复。
 * 用两个阈值做迟滞，避免在阈值附近反复暂停 / 恢复。不再主动调用 System.gc()。
 */
@Component
public class FileProcessingBackpressureMonitor {

    private static final Logger logger = LoggerFactory.getLogger(FileProcessingBackpressureMonitor.class);

    private final FileProcessingListenerGate gate;
    private final boolean enabled;
    private final double pauseThreshold;
    private final double resumeThreshold;
    private final long checkIntervalMillis;
    private final long maxPauseMillis;
    private final DoubleSupplier heapUsageSupplier;
    private final LongSupplier clock;

    private ScheduledExecutorService scheduler;
    private boolean pausedByMemory;
    private long pausedAtMillis;

    @Autowired
    public FileProcessingBackpressureMonitor(
            FileProcessingListenerGate gate,
            @Value("${file.parsing.backpressure.enabled:true}") boolean enabled,
            @Value("${file.parsing.max-memory-threshold:0.8}") double pauseThreshold,
            @Value("${file.parsing.backpressure.resume-threshold:0.7}") double resumeThreshold,
            @Value("${file.parsing.backpressure.check-interval-ms:1000}") long checkIntervalMillis,
            @Value("${file.parsing.backpressure.max-pause-seconds:300}") long maxPauseSeconds) {
        this(gate, enabled, pauseThreshold, resumeThreshold, checkIntervalMillis, maxPauseSeconds,
                FileProcessingBackpressureMonitor::currentHeapUsage, System::currentTimeMillis);
    }

    FileProcessingBackpressureMonitor(FileProcessingListenerGate gate,
                                      boolean enabled,
                                      double pauseThreshold,
                                      double resumeThreshold,
                                      long checkIntervalMillis,
                                      long maxPauseSeconds,
                                      DoubleSupplier heapUsageSupplier,
                                      LongSupplier clock) {
        if (resumeThreshold >= pauseThreshold) {
            throw new IllegalArgumentException("file.parsing.backpressure.resume-threshold 必须小于 file.parsing.max-memory-threshold");
        }
        this.gate = gate;
        this.enabled = enabled;
        this.pauseThreshold = pauseThreshold;
        this.resumeThreshold = resumeThreshold;
        this.checkIntervalMillis = checkIntervalMillis;
        this.maxPauseMillis = TimeUnit.SECONDS.toMillis(Math.max(maxPauseSeconds, 0));
        this.heapUsageSupplier = heapUsageSupplier;
        this.clock = clock;
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            logger.info("文件处理堆内存背压已关闭");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "file-processing-backpressure");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::safeCheck, checkIntervalMillis, checkIntervalMillis, TimeUnit.MILLISECONDS);
        logger.info("文件处理堆内存背压已启动: pauseThreshold={}, resumeThreshold={}, intervalMs={}, maxPauseSeconds={}",
                pauseThreshold, resumeThreshold, checkIntervalMillis, maxPauseMillis / 1000);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void safeCheck() {
        try {
            check();
        } catch (Exception e) {
            logger.warn("堆内存背压检查失败: {}", e.getMessage(), e);
        }
    }

    synchronized void check() {
        double usage = heapUsageSupplier.getAsDouble();
        long now = clock.getAsLong();

        if (!pausedByMemory) {
            if (usage >= pauseThreshold) {
                pausedByMemory = true;
                pausedAtMillis = now;
                logger.warn("堆内存使用率 {} 超过阈值 {}，暂停文件处理消费", percent(usage), percent(pauseThreshold));
                gate.pause(FileProcessingListenerGate.REASON_HEAP_PRESSURE);
            }
            return;
        }

        if (usage <= resumeThreshold) {
            pausedByMemory = false;
            logger.info("堆内存使用率回落到 {}（恢复阈值 {}），恢复文件处理消费", percent(usage), percent(resumeThreshold));
            gate.resume(FileProcessingListenerGate.REASON_HEAP_PRESSURE);
            return;
        }

        // 安全阀：空闲 JVM 可能长时间不触发 GC，已用堆里的垃圾迟迟不回收，避免消费被永久卡住
        if (maxPauseMillis > 0 && now - pausedAtMillis >= maxPauseMillis) {
            pausedByMemory = false;
            logger.error("文件处理消费已暂停 {} 秒，堆内存使用率仍为 {}，强制恢复一次；请检查是否存在内存泄漏或调大堆",
                    (now - pausedAtMillis) / 1000, percent(usage));
            gate.resume(FileProcessingListenerGate.REASON_HEAP_PRESSURE);
        }
    }

    synchronized boolean isPausedByMemory() {
        return pausedByMemory;
    }

    static double currentHeapUsage() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long max = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        return max <= 0 ? 0d : (double) heap.getUsed() / max;
    }

    private static String percent(double ratio) {
        return String.format("%.1f%%", ratio * 100);
    }
}
