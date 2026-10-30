package com.yizhaoqi.smartpai.service.reliability;

import com.yizhaoqi.smartpai.config.FileProcessingReliabilityProperties;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.DocumentService;
import com.yizhaoqi.smartpai.service.VectorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 向量化任务的认领、心跳和收尾。
 * <p>
 * 所有状态变更都是带条件的 UPDATE：认领要求没有别的消费者持有未超时的心跳；心跳、完成、失败要求认领时间没变。
 * 定时任务把卡住的任务改回待处理时会清空认领时间，旧消费者下一次心跳就会发现并放弃，不会和新消费者同时写数据
 */
@Service
public class VectorizationLeaseService {

    private static final Logger logger = LoggerFactory.getLogger(VectorizationLeaseService.class);

    private final FileUploadRepository fileUploadRepository;
    private final FileProcessingReliabilityProperties properties;
    private final DocumentStatusNotifier statusNotifier;

    public VectorizationLeaseService(FileUploadRepository fileUploadRepository,
                                     FileProcessingReliabilityProperties properties,
                                     DocumentStatusNotifier statusNotifier) {
        this.fileUploadRepository = fileUploadRepository;
        this.properties = properties;
        this.statusNotifier = statusNotifier;
    }

    /**
     * 认领任务。上传任务已向量化完成时直接跳过（Kafka 重复投递）；有其他消费者正在处理时也跳过
     */
    public Optional<VectorizationLease> claim(FileProcessingTask task) {
        Optional<FileUpload> record = resolveRecord(task);
        if (record.isEmpty()) {
            logger.warn("文件记录不存在，跳过处理任务: fileMd5={}, userId={}", task.getFileMd5(), task.getUserId());
            return Optional.empty();
        }

        FileUpload fileUpload = record.get();
        boolean uploadTask = !FileProcessingTask.TASK_TYPE_REINDEX.equals(task.getTaskType());
        if (uploadTask && FileUpload.VECTORIZATION_STATUS_COMPLETED.equals(fileUpload.getVectorizationStatus())) {
            logger.info("文件已向量化完成，跳过重复投递的上传任务: fileMd5={}, fileUploadId={}", task.getFileMd5(), fileUpload.getId());
            return Optional.empty();
        }

        LocalDateTime now = now();
        LocalDateTime staleBefore = now.minusSeconds(properties.getVectorizationHeartbeatTimeoutSeconds());
        if (fileUploadRepository.claimVectorization(fileUpload.getId(), now, staleBefore) == 0) {
            logger.info("文件正在被其他消费者处理且心跳未超时，跳过本条消息: fileMd5={}, fileUploadId={}",
                    task.getFileMd5(), fileUpload.getId());
            return Optional.empty();
        }

        // 认领时间以数据库存下的值为准，避免列精度和 Java 时间精度不一致导致后续条件更新永远不命中
        LocalDateTime claimedAt = fileUploadRepository.findById(fileUpload.getId())
                .map(FileUpload::getProcessingStartedAt)
                .orElse(now);
        logger.info("已认领向量化任务: fileMd5={}, fileUploadId={}, taskType={}", task.getFileMd5(), fileUpload.getId(), task.getTaskType());
        statusNotifier.notifyChanged(fileUpload.getId());
        return Optional.of(new VectorizationLease(fileUpload.getId(), fileUpload.getFileMd5(), claimedAt));
    }

    /**
     * 刷新心跳；认领已失效时抛出 {@link VectorizationLeaseLostException}
     */
    public void heartbeat(VectorizationLease lease) {
        if (fileUploadRepository.heartbeatVectorization(lease.fileUploadId(), lease.claimedAt(), now()) == 0) {
            throw new VectorizationLeaseLostException(lease.fileMd5());
        }
    }

    /**
     * @return true 表示完成状态已写入；false 表示认领已失效，结果由新的认领者负责
     */
    public boolean complete(VectorizationLease lease, VectorizationService.VectorizationUsageResult result) {
        int updated = fileUploadRepository.completeVectorizationIfOwner(
                lease.fileUploadId(),
                lease.claimedAt(),
                (long) result.actualEmbeddingTokens(),
                result.actualChunkCount(),
                now()
        );
        if (updated == 0) {
            logger.warn("向量化已完成但认领已失效，不写完成状态: fileMd5={}, fileUploadId={}", lease.fileMd5(), lease.fileUploadId());
            return false;
        }
        statusNotifier.notifyChanged(lease.fileUploadId());
        return true;
    }

    public void fail(VectorizationLease lease, Throwable error) {
        int updated = fileUploadRepository.failVectorizationIfOwner(
                lease.fileUploadId(),
                lease.claimedAt(),
                DocumentService.vectorizationErrorMessageOf(error),
                now()
        );
        if (updated == 0) {
            logger.warn("向量化失败但认领已失效，不写失败状态: fileMd5={}, fileUploadId={}", lease.fileMd5(), lease.fileUploadId());
            return;
        }
        statusNotifier.notifyChanged(lease.fileUploadId());
    }

    /**
     * 优先取任务里上传者的那条记录，旧消息没有 userId 时退回该 MD5 最新的记录
     */
    private Optional<FileUpload> resolveRecord(FileProcessingTask task) {
        if (task.getUserId() != null && !task.getUserId().isBlank()) {
            Optional<FileUpload> owned = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(
                    task.getFileMd5(), task.getUserId());
            if (owned.isPresent()) {
                return owned;
            }
        }
        return fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(task.getFileMd5());
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
