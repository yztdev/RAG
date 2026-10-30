package com.yizhaoqi.smartpai.config;

import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncExecutorConfig {

    @Bean(name = "chatMonitorExecutor")
    public ThreadPoolTaskExecutor chatMonitorExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("chat-monitor-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setAwaitTerminationSeconds(10);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    /**
     * 引用事后校验。ReAct 循环本身跑在 chatMonitorExecutor 上，校验必须用独立线程池，避免同池互等导致饥饿。
     */
    @Bean(name = "citationVerificationExecutor")
    public ThreadPoolTaskExecutor citationVerificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("citation-verify-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setAwaitTerminationSeconds(10);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    /**
     * 混合检索 BM25 / KNN 双路并行召回。队列满时由调用线程执行，退化为串行而不是拒绝请求。
     */
    @Bean(name = "searchRecallExecutor")
    public ThreadPoolTaskExecutor searchRecallExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("search-recall-");
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(200);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setAwaitTerminationSeconds(10);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    /**
     * 文档冲突检测。单线程串行扫描，避免批量上传时 KNN 查询挤占线上检索；
     * 队列满时丢弃并记日志（冲突检测是旁路任务，不能反压到 Kafka 消费线程）。
     */
    @Bean(name = "conflictDetectionExecutor")
    public ThreadPoolTaskExecutor conflictDetectionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("doc-conflict-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(500);
        executor.setRejectedExecutionHandler((task, pool) -> LoggerFactory.getLogger(AsyncExecutorConfig.class)
                .warn("冲突检测队列已满，丢弃本次检测任务"));
        executor.setAwaitTerminationSeconds(10);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * 离线评测里的生成 / 评委调用，带超时等待，和线上问答的线程池隔离
     */
    @Bean(name = "evalLlmExecutor")
    public ThreadPoolTaskExecutor evalLlmExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("rag-eval-llm-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setAwaitTerminationSeconds(10);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * outbox 提交后立即投递 Kafka。队列满时直接丢弃，消息仍在 outbox 表里，由定时补发任务兜底
     */
    @Bean(name = "outboxDispatchExecutor")
    public ThreadPoolTaskExecutor outboxDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("outbox-dispatch-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setAwaitTerminationSeconds(10);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
