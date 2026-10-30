package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.FileProcessingOutbox;
import com.yizhaoqi.smartpai.model.FileUpload;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在 H2 上实际执行状态机相关的条件更新，验证 JPQL 能解析、条件按预期生效
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FileUploadRepositoryReliabilityTest {

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private FileProcessingOutboxRepository outboxRepository;

    private final LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);

    @Test
    void mergeCompletionOnlyAppliesWhileMerging() {
        FileUpload file = save("md5-a", "1", FileUpload.STATUS_MERGING, null, null);

        assertThat(fileUploadRepository.completeMergeIfMerging(file.getId(), FileUpload.STATUS_MERGING, FileUpload.STATUS_COMPLETED, now))
                .isEqualTo(1);
        // 第二次（重复请求）影响行数为 0
        assertThat(fileUploadRepository.completeMergeIfMerging(file.getId(), FileUpload.STATUS_MERGING, FileUpload.STATUS_COMPLETED, now))
                .isZero();
        assertThat(fileUploadRepository.findById(file.getId()).orElseThrow().getStatus()).isEqualTo(FileUpload.STATUS_COMPLETED);
    }

    @Test
    void claimRejectsFreshLeaseAndHeartbeatFencesOnClaimTime() {
        FileUpload file = save("md5-b", "1", FileUpload.STATUS_COMPLETED, FileUpload.VECTORIZATION_STATUS_PENDING, null);

        assertThat(fileUploadRepository.claimVectorization(file.getId(), now, now.minusMinutes(10))).isEqualTo(1);
        LocalDateTime claimedAt = fileUploadRepository.findById(file.getId()).orElseThrow().getProcessingStartedAt();
        assertThat(claimedAt).isNotNull();

        // 心跳未超时，另一个消费者认领失败
        assertThat(fileUploadRepository.claimVectorization(file.getId(), now.plusSeconds(1), now.minusMinutes(10))).isZero();
        assertThat(fileUploadRepository.heartbeatVectorization(file.getId(), claimedAt, now.plusSeconds(5))).isEqualTo(1);
        // 认领时间对不上（旧消费者）时心跳失败
        assertThat(fileUploadRepository.heartbeatVectorization(file.getId(), claimedAt.minusSeconds(1), now.plusSeconds(5))).isZero();

        assertThat(fileUploadRepository.completeVectorizationIfOwner(file.getId(), claimedAt, 100L, 3, now.plusSeconds(6))).isEqualTo(1);
        FileUpload completed = fileUploadRepository.findById(file.getId()).orElseThrow();
        assertThat(completed.getVectorizationStatus()).isEqualTo(FileUpload.VECTORIZATION_STATUS_COMPLETED);
        assertThat(completed.getActualChunkCount()).isEqualTo(3);
    }

    @Test
    void staleVectorizationIsRequeuedOnceAndOldWorkerLosesLease() {
        LocalDateTime staleHeartbeat = now.minusMinutes(30);
        FileUpload file = save("md5-c", "1", FileUpload.STATUS_COMPLETED, FileUpload.VECTORIZATION_STATUS_PROCESSING, staleHeartbeat);
        file.setProcessingStartedAt(staleHeartbeat);
        fileUploadRepository.saveAndFlush(file);

        List<FileUpload> stale = fileUploadRepository.findStaleVectorizations(now.minusMinutes(10), PageRequest.of(0, 10));
        assertThat(stale).extracting(FileUpload::getId).containsExactly(file.getId());
        LocalDateTime observed = stale.get(0).getHeartbeatAt();

        assertThat(fileUploadRepository.requeueStaleVectorization(file.getId(), observed, now)).isEqualTo(1);
        // 另一个实例拿着同样的旧心跳再改一次，条件不成立
        assertThat(fileUploadRepository.requeueStaleVectorization(file.getId(), observed, now)).isZero();

        FileUpload requeued = fileUploadRepository.findById(file.getId()).orElseThrow();
        assertThat(requeued.getVectorizationStatus()).isEqualTo(FileUpload.VECTORIZATION_STATUS_PENDING);
        assertThat(requeued.getRetryCount()).isEqualTo(1);
        assertThat(requeued.getProcessingStartedAt()).isNull();
        // 旧消费者恢复后心跳失败，主动放弃
        assertThat(fileUploadRepository.heartbeatVectorization(file.getId(), staleHeartbeat, now)).isZero();
    }

    @Test
    void conditionalUpdatesHandleRecordsWithoutHeartbeat() {
        FileUpload legacy = save("md5-d", "1", FileUpload.STATUS_COMPLETED, FileUpload.VECTORIZATION_STATUS_PROCESSING, null);

        assertThat(fileUploadRepository.failStaleVectorization(legacy.getId(), null, "超时", now)).isEqualTo(1);
        assertThat(fileUploadRepository.findById(legacy.getId()).orElseThrow().getVectorizationStatus())
                .isEqualTo(FileUpload.VECTORIZATION_STATUS_FAILED);
    }

    @Test
    void staleMergeIsResetAndAbandonedUploadIsDeletedOnlyIfUntouched() {
        LocalDateTime old = now.minusDays(2);
        FileUpload merging = save("md5-e", "1", FileUpload.STATUS_MERGING, null, old);
        FileUpload uploading = save("md5-f", "1", FileUpload.STATUS_UPLOADING, null, old);

        assertThat(fileUploadRepository.findStaleByUploadStatus(FileUpload.STATUS_MERGING, now.minusMinutes(10), PageRequest.of(0, 10)))
                .extracting(FileUpload::getId).containsExactly(merging.getId());
        assertThat(fileUploadRepository.resetStaleMerge(merging.getId(), FileUpload.STATUS_MERGING, FileUpload.STATUS_UPLOADING, old, now))
                .isEqualTo(1);
        assertThat(fileUploadRepository.findById(merging.getId()).orElseThrow().getStatus()).isEqualTo(FileUpload.STATUS_UPLOADING);

        // 扫描后又有新分片到达，心跳变了，不删
        fileUploadRepository.touchUploadHeartbeat(uploading.getId(), FileUpload.STATUS_UPLOADING, now);
        assertThat(fileUploadRepository.deleteAbandonedUpload(uploading.getId(), FileUpload.STATUS_UPLOADING, old)).isZero();
        assertThat(fileUploadRepository.deleteAbandonedUpload(uploading.getId(), FileUpload.STATUS_UPLOADING, now)).isEqualTo(1);
        assertThat(fileUploadRepository.findById(uploading.getId())).isEmpty();
    }

    @Test
    void outboxClaimIsExclusiveAndSentRowsLeaveTheQueue() {
        FileProcessingOutbox outbox = new FileProcessingOutbox();
        outbox.setFileMd5("md5-g");
        outbox.setTaskType("UPLOAD_PROCESS");
        outbox.setPayload("{}");
        outbox.setNextAttemptAt(now.minusSeconds(1));
        outbox = outboxRepository.saveAndFlush(outbox);

        List<FileProcessingOutbox> due = outboxRepository.findDue(now, PageRequest.of(0, 10));
        assertThat(due).extracting(FileProcessingOutbox::getId).containsExactly(outbox.getId());
        LocalDateTime observed = due.get(0).getNextAttemptAt();

        assertThat(outboxRepository.claim(outbox.getId(), observed, now.plusMinutes(1))).isEqualTo(1);
        assertThat(outboxRepository.claim(outbox.getId(), observed, now.plusMinutes(1))).isZero();
        assertThat(outboxRepository.markSent(outbox.getId(), now)).isEqualTo(1);
        assertThat(outboxRepository.findDue(now.plusHours(1), PageRequest.of(0, 10))).isEmpty();
    }

    private FileUpload save(String md5, String userId, int status, String vectorizationStatus, LocalDateTime heartbeatAt) {
        FileUpload file = new FileUpload();
        file.setFileMd5(md5);
        file.setFileName(md5 + ".pdf");
        file.setUserId(userId);
        file.setStatus(status);
        file.setVectorizationStatus(vectorizationStatus);
        file.setHeartbeatAt(heartbeatAt);
        return fileUploadRepository.saveAndFlush(file);
    }
}
