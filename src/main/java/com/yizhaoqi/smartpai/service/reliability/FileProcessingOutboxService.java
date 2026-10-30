package com.yizhaoqi.smartpai.service.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.FileProcessingReliabilityProperties;
import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.model.FileProcessingOutbox;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileProcessingOutboxRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 文件处理任务的 outbox 投递。
 * <p>
 * 改文件状态和写待投递消息放在同一个数据库事务里，事务提交后异步投递 Kafka；
 * 投递失败或进程在提交后崩溃，消息仍在表里，定时任务按指数退避补发。投递是至少一次，消费端靠认领和完成状态去重
 */
@Service
public class FileProcessingOutboxService {

    private static final Logger logger = LoggerFactory.getLogger(FileProcessingOutboxService.class);
    private static final int ALERT_ATTEMPTS = 5;

    private final FileProcessingOutboxRepository outboxRepository;
    private final FileUploadRepository fileUploadRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaConfig kafkaConfig;
    private final ObjectMapper objectMapper;
    private final FileProcessingReliabilityProperties properties;
    private final Executor dispatchExecutor;
    private final DocumentStatusNotifier statusNotifier;

    public FileProcessingOutboxService(FileProcessingOutboxRepository outboxRepository,
                                       FileUploadRepository fileUploadRepository,
                                       KafkaTemplate<String, Object> kafkaTemplate,
                                       KafkaConfig kafkaConfig,
                                       ObjectMapper objectMapper,
                                       FileProcessingReliabilityProperties properties,
                                       @Qualifier("outboxDispatchExecutor") Executor dispatchExecutor,
                                       DocumentStatusNotifier statusNotifier) {
        this.outboxRepository = outboxRepository;
        this.fileUploadRepository = fileUploadRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaConfig = kafkaConfig;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.dispatchExecutor = dispatchExecutor;
        this.statusNotifier = statusNotifier;
    }

    /**
     * 把文件标记为待处理并写入待投递消息，二者同一事务提交。重试次数清零：这是一次新的处理请求，不是卡住后的自动重投
     */
    @Transactional
    public void enqueueVectorization(FileUpload fileUpload, FileProcessingTask task, boolean resetActualUsage) {
        fileUpload.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_PENDING);
        fileUpload.setVectorizationErrorMessage(null);
        fileUpload.setProcessingStartedAt(null);
        fileUpload.setHeartbeatAt(now());
        fileUpload.setRetryCount(0);
        if (resetActualUsage) {
            fileUpload.setActualEmbeddingTokens(null);
            fileUpload.setActualChunkCount(null);
        }
        fileUploadRepository.save(fileUpload);
        append(task);
        statusNotifier.notifyChanged(fileUpload.getId());
    }

    /**
     * 只写待投递消息，加入调用方的事务（恢复任务在条件更新成功的同一事务里调用）
     */
    @Transactional
    public FileProcessingOutbox append(FileProcessingTask task) {
        FileProcessingOutbox outbox = new FileProcessingOutbox();
        outbox.setFileMd5(task.getFileMd5());
        outbox.setTaskType(task.getTaskType() == null ? FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS : task.getTaskType());
        outbox.setPayload(writePayload(task));
        outbox.setStatus(FileProcessingOutbox.STATUS_PENDING);
        outbox.setNextAttemptAt(now());
        FileProcessingOutbox saved = outboxRepository.save(outbox);
        dispatchAfterCommit(saved.getId());
        return saved;
    }

    @Scheduled(fixedDelayString = "${file.processing.reliability.outbox.dispatch-interval-ms:5000}",
            initialDelayString = "${file.processing.reliability.outbox.dispatch-interval-ms:5000}")
    public void dispatchDue() {
        if (!properties.getOutbox().isDispatcherEnabled()) {
            return;
        }
        List<FileProcessingOutbox> due;
        try {
            due = outboxRepository.findDue(now(), PageRequest.of(0, Math.max(properties.getOutbox().getBatchSize(), 1)));
        } catch (Exception e) {
            logger.warn("查询待补发的文件处理消息失败: {}", e.getMessage());
            return;
        }
        for (FileProcessingOutbox outbox : due) {
            dispatch(outbox);
        }
    }

    @Scheduled(cron = "${file.processing.reliability.outbox.purge-cron:0 30 3 * * *}")
    public void purgeSent() {
        int deleted = outboxRepository.deleteSentBefore(now().minusDays(Math.max(properties.getOutbox().getSentRetentionDays(), 1)));
        if (deleted > 0) {
            logger.info("已清理过期的已投递 outbox 记录: {}", deleted);
        }
    }

    /**
     * 认领并投递一条消息
     *
     * @return true 表示本次投递成功
     */
    boolean dispatch(FileProcessingOutbox outbox) {
        if (!FileProcessingOutbox.STATUS_PENDING.equals(outbox.getStatus())) {
            return false;
        }
        LocalDateTime leaseUntil = now().plusSeconds(Math.max(properties.getOutbox().getClaimLeaseSeconds(), 1L));
        if (outboxRepository.claim(outbox.getId(), outbox.getNextAttemptAt(), leaseUntil) == 0) {
            return false;
        }

        try {
            FileProcessingTask task = objectMapper.readValue(outbox.getPayload(), FileProcessingTask.class);
            long timeoutSeconds = Math.max(properties.getOutbox().getSendTimeoutSeconds(), 1L);
            // 以 fileMd5 作为消息 key：同一个文件的任务落在同一分区，按投递顺序处理
            kafkaTemplate.executeInTransaction(operations -> {
                try {
                    return operations.send(kafkaConfig.getFileProcessingTopic(), task.getFileMd5(), task)
                            .get(timeoutSeconds, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待 Kafka 确认被中断", e);
                } catch (Exception e) {
                    throw new IllegalStateException("Kafka 发送失败: " + e.getMessage(), e);
                }
            });
            outboxRepository.markSent(outbox.getId(), now());
            logger.info("文件处理任务已投递: outboxId={}, fileMd5={}, taskType={}", outbox.getId(), outbox.getFileMd5(), outbox.getTaskType());
            return true;
        } catch (Exception e) {
            int attempts = outbox.getAttempts() + 1;
            LocalDateTime nextAttemptAt = now().plusSeconds(backoffSeconds(attempts));
            outboxRepository.markAttemptFailed(outbox.getId(), nextAttemptAt, truncate(e.getMessage()));
            if (attempts >= ALERT_ATTEMPTS) {
                logger.error("[ALERT] 文件处理任务多次投递 Kafka 失败: outboxId={}, fileMd5={}, attempts={}, nextAttemptAt={}, error={}",
                        outbox.getId(), outbox.getFileMd5(), attempts, nextAttemptAt, e.getMessage());
            } else {
                logger.warn("文件处理任务投递 Kafka 失败，稍后补发: outboxId={}, fileMd5={}, attempts={}, nextAttemptAt={}, error={}",
                        outbox.getId(), outbox.getFileMd5(), attempts, nextAttemptAt, e.getMessage());
            }
            return false;
        }
    }

    private void dispatchAfterCommit(Long outboxId) {
        Runnable dispatchNow = () -> dispatchExecutor.execute(() -> outboxRepository.findById(outboxId).ifPresent(this::dispatch));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatchNow.run();
                }
            });
            return;
        }
        dispatchNow.run();
    }

    /**
     * 指数退避加抖动：5s、10s、20s……封顶 maxBackoffSeconds，抖动避免多条失败消息同时补发
     */
    long backoffSeconds(int attempts) {
        long cap = Math.max(properties.getOutbox().getMaxBackoffSeconds(), 5L);
        long base = Math.min(cap, 5L << Math.min(Math.max(attempts - 1, 0), 16));
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(base / 2, 1) + 1);
        return Math.min(cap, base / 2 + jitter);
    }

    private String writePayload(FileProcessingTask task) {
        try {
            return objectMapper.writeValueAsString(task);
        } catch (Exception e) {
            throw new IllegalStateException("序列化文件处理任务失败: " + task.getFileMd5(), e);
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
