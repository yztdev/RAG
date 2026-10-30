package com.yizhaoqi.smartpai.utils;

import java.time.Duration;
import java.util.Optional;

/**
 * 请求级截止时间，绑定在当前线程上。
 * <p>
 * 问答请求在入口处算出截止时间，ReAct 循环所在线程打开作用域后，检索、embedding、重排序等同步调用都能读到"整个请求还剩多少时间"，
 * 每一步的超时取自身配置和剩余时间中较小的值。作用域可以嵌套，内层截止时间只能更早（例如工具执行要给最终回答留出时间）。
 * 没有打开作用域时（离线评测、后台任务）各步骤按自身配置执行，行为不变
 */
public final class RequestDeadline {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private RequestDeadline() {
    }

    /**
     * 从现在起经过 budget 后的截止时间（System.nanoTime 刻度）
     */
    public static long after(Duration budget) {
        return System.nanoTime() + budget.toNanos();
    }

    /**
     * 在当前线程打开截止时间作用域；已有外层截止时间时取两者中更早的一个。关闭时恢复外层
     */
    public static Scope open(long deadlineNanos) {
        Long previous = CURRENT.get();
        CURRENT.set(previous == null ? deadlineNanos : Math.min(previous, deadlineNanos));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /**
     * 剩余时间；没有截止时间时为空，已经超时返回 0
     */
    public static Optional<Duration> remaining() {
        Long deadline = CURRENT.get();
        if (deadline == null) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofNanos(Math.max(0L, deadline - System.nanoTime())));
    }

    /**
     * 自身配置的超时和剩余时间取较小值
     */
    public static Duration cap(Duration configured) {
        return remaining().map(left -> left.compareTo(configured) < 0 ? left : configured).orElse(configured);
    }

    /**
     * 没有截止时间，或剩余时间不少于 required
     */
    public static boolean hasAtLeast(Duration required) {
        return remaining().map(left -> left.compareTo(required) >= 0).orElse(true);
    }

    public static Optional<Long> currentNanos() {
        return Optional.ofNullable(CURRENT.get());
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
