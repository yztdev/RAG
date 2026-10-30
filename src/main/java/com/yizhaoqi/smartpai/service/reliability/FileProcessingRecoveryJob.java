package com.yizhaoqi.smartpai.service.reliability;

import com.yizhaoqi.smartpai.config.FileProcessingReliabilityProperties;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.UploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 卡住任务的发现和恢复、废弃上传的清理。
 * <p>
 * 每类记录都是先查出来，再用"扫描时看到的心跳"做条件更新：扫描之后任务有了进展、或者另一个实例已经处理过，
 * 条件就不成立，影响行数为 0 直接跳过。多实例同时跑也不会重复投递或误删
 */
@Component
public class FileProcessingRecoveryJob {

    private static final Logger logger = LoggerFactory.getLogger(FileProcessingRecoveryJob.class);
    static final String LEGACY_TASK_MESSAGE = "升级前中断的历史任务，已停止自动重试，如需要请手动重试向量化";

    private final FileUploadRepository fileUploadRepository;
    private final FileProcessingOutboxService outboxService;
    private final UploadService uploadService;
    private final FileProcessingReliabilityProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final DocumentStatusNotifier statusNotifier;

    public FileProcessingRecoveryJob(FileUploadRepository fileUploadRepository,
                                     FileProcessingOutboxService outboxService,
                                     UploadService uploadService,
                                     FileProcessingReliabilityProperties properties,
                                     TransactionTemplate transactionTemplate,
                                     DocumentStatusNotifier statusNotifier) {
        this.fileUploadRepository = fileUploadRepository;
        this.outboxService = outboxService;
        this.uploadService = uploadService;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.statusNotifier = statusNotifier;
    }

    @Scheduled(fixedDelayString = "${file.processing.reliability.recovery-interval-ms:60000}",
            initialDelayString = "${file.processing.reliability.recovery-interval-ms:60000}")
    public void run() {
        if (!properties.isRecoveryEnabled()) {
            return;
        }
        runStep("向量化卡住恢复", this::recoverStaleVectorizations);
        runStep("合并卡住恢复", this::recoverStaleMerges);
        runStep("废弃上传清理", this::cleanupAbandonedUploads);
    }

    /**
     * 心跳超时的 PROCESSING：升级前遗留的直接标失败；未超重试上限的改回 PENDING 并重新投递，超过上限的标记失败并告警
     */
    int recoverStaleVectorizations() {
        LocalDateTime now = now();
        LocalDateTime staleBefore = now.minusSeconds(properties.getVectorizationHeartbeatTimeoutSeconds());
        List<FileUpload> stale = fileUploadRepository.findStaleVectorizations(staleBefore, page());
        int handled = 0;
        for (FileUpload file : stale) {
            if (file.getProcessingStartedAt() == null) {
                // 没有认领时间说明是升级前旧代码留下的 PROCESSING：不知道卡了多久、用户还要不要，
                // 自动重投会一次性把几个月的历史任务全部捞回队列，挡住新上传。直接标失败，由用户决定是否重试
                if (fileUploadRepository.failStaleVectorization(file.getId(), file.getHeartbeatAt(), LEGACY_TASK_MESSAGE, now) == 1) {
                    handled++;
                    statusNotifier.notifyChanged(file.getId());
                    logger.warn("升级前中断的历史向量化任务，已标记失败不再自动重投: fileMd5={}, fileUploadId={}, lastUpdated={}",
                            file.getFileMd5(), file.getId(), file.getMergedAt());
                }
                continue;
            }
            if (file.getRetryCount() >= properties.getMaxRetries()) {
                String message = String.format("处理超时，已自动重试 %d 次仍未完成，请手动重试", file.getRetryCount());
                if (fileUploadRepository.failStaleVectorization(file.getId(), file.getHeartbeatAt(), message, now) == 1) {
                    handled++;
                    statusNotifier.notifyChanged(file.getId());
                    logger.error("[ALERT] 向量化任务多次卡住，已标记失败: fileMd5={}, fileUploadId={}, retryCount={}, lastHeartbeat={}",
                            file.getFileMd5(), file.getId(), file.getRetryCount(), file.getHeartbeatAt());
                }
                continue;
            }

            Boolean requeued = transactionTemplate.execute(status -> {
                if (fileUploadRepository.requeueStaleVectorization(file.getId(), file.getHeartbeatAt(), now) == 0) {
                    return false;
                }
                outboxService.append(buildRetryTask(file));
                return true;
            });
            if (Boolean.TRUE.equals(requeued)) {
                handled++;
                statusNotifier.notifyChanged(file.getId());
                logger.warn("向量化任务心跳超时，已改回待处理并重新投递: fileMd5={}, fileUploadId={}, retryCount={}, lastHeartbeat={}",
                        file.getFileMd5(), file.getId(), file.getRetryCount() + 1, file.getHeartbeatAt());
            }
        }
        return handled;
    }

    /**
     * 合并中超时：合并成功前分片不会删除，改回上传中让用户重新发起合并
     */
    int recoverStaleMerges() {
        LocalDateTime now = now();
        LocalDateTime staleBefore = now.minusSeconds(properties.getMergeTimeoutSeconds());
        List<FileUpload> stale = fileUploadRepository.findStaleByUploadStatus(FileUpload.STATUS_MERGING, staleBefore, page());
        int handled = 0;
        for (FileUpload file : stale) {
            int updated = fileUploadRepository.resetStaleMerge(
                    file.getId(), FileUpload.STATUS_MERGING, FileUpload.STATUS_UPLOADING, file.getHeartbeatAt(), now);
            if (updated == 1) {
                handled++;
                logger.warn("合并超时，已改回上传中: fileMd5={}, userId={}, fileUploadId={}, mergeStartedAt={}",
                        file.getFileMd5(), file.getUserId(), file.getId(), file.getHeartbeatAt());
            }
        }
        return handled;
    }

    /**
     * 长时间没有新分片的上传：先条件删除记录，删成功再清理分片对象、分片记录和 bitmap
     */
    int cleanupAbandonedUploads() {
        LocalDateTime staleBefore = now().minusHours(properties.getAbandonedUploadHours());
        List<FileUpload> stale = fileUploadRepository.findStaleByUploadStatus(FileUpload.STATUS_UPLOADING, staleBefore, page());
        int handled = 0;
        for (FileUpload file : stale) {
            if (fileUploadRepository.deleteAbandonedUpload(file.getId(), FileUpload.STATUS_UPLOADING, file.getHeartbeatAt()) == 0) {
                continue;
            }
            handled++;
            uploadService.cleanupAbandonedChunks(file.getFileMd5(), file.getUserId());
            logger.info("已清理废弃上传: fileMd5={}, userId={}, fileName={}, lastHeartbeat={}",
                    file.getFileMd5(), file.getUserId(), file.getFileName(), file.getHeartbeatAt());
        }
        return handled;
    }

    /**
     * 重新投递统一走上传处理任务，文件从 MinIO 的合并对象读取，不依赖会过期的预签名 URL
     */
    private FileProcessingTask buildRetryTask(FileUpload file) {
        return new FileProcessingTask(
                file.getFileMd5(),
                null,
                file.getFileName(),
                file.getUserId(),
                file.getOrgTag(),
                file.isPublic(),
                FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS,
                file.getUserId()
        );
    }

    private void runStep(String name, java.util.function.IntSupplier step) {
        try {
            int handled = step.getAsInt();
            if (handled > 0) {
                logger.info("{}完成，本轮处理 {} 条", name, handled);
            }
        } catch (Exception e) {
            logger.error("{}执行失败: {}", name, e.getMessage(), e);
        }
    }

    private PageRequest page() {
        return PageRequest.of(0, Math.max(properties.getRecoveryBatchSize(), 1));
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
