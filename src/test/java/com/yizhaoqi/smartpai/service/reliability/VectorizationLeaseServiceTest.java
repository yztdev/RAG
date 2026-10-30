package com.yizhaoqi.smartpai.service.reliability;

import com.yizhaoqi.smartpai.config.FileProcessingReliabilityProperties;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorizationLeaseServiceTest {

    private final FileUploadRepository repository = mock(FileUploadRepository.class);
    private final VectorizationLeaseService service = new VectorizationLeaseService(repository, new FileProcessingReliabilityProperties(), mock(DocumentStatusNotifier.class));

    @Test
    void completedUploadTaskIsSkippedButReindexIsClaimed() {
        FileUpload file = file(FileUpload.VECTORIZATION_STATUS_COMPLETED);
        when(repository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1")).thenReturn(Optional.of(file));

        assertTrue(service.claim(task(FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS)).isEmpty());
        verify(repository, never()).claimVectorization(anyLong(), any(), any());

        LocalDateTime stored = LocalDateTime.of(2026, 10, 6, 12, 0, 0, 123_000_000);
        when(repository.claimVectorization(eq(5L), any(), any())).thenReturn(1);
        FileUpload claimed = file(FileUpload.VECTORIZATION_STATUS_PROCESSING);
        claimed.setProcessingStartedAt(stored);
        when(repository.findById(5L)).thenReturn(Optional.of(claimed));

        VectorizationLease lease = service.claim(task(FileProcessingTask.TASK_TYPE_REINDEX)).orElseThrow();
        // 认领凭证取数据库里存下的值
        assertEquals(stored, lease.claimedAt());
    }

    @Test
    void claimFailsWhileAnotherWorkerHoldsFreshHeartbeat() {
        when(repository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(file(FileUpload.VECTORIZATION_STATUS_PROCESSING)));
        when(repository.claimVectorization(eq(5L), any(), any())).thenReturn(0);

        assertTrue(service.claim(task(FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS)).isEmpty());
    }

    @Test
    void heartbeatWithStaleClaimThrowsLeaseLost() {
        VectorizationLease lease = new VectorizationLease(5L, "md5", LocalDateTime.now());
        when(repository.heartbeatVectorization(eq(5L), eq(lease.claimedAt()), any())).thenReturn(0);

        assertThrows(VectorizationLeaseLostException.class, () -> service.heartbeat(lease));
    }

    private FileProcessingTask task(String type) {
        return new FileProcessingTask("md5", null, "a.pdf", "1", "TEAM", false, type, "1");
    }

    private FileUpload file(String vectorizationStatus) {
        FileUpload file = new FileUpload();
        file.setId(5L);
        file.setFileMd5("md5");
        file.setUserId("1");
        file.setStatus(FileUpload.STATUS_COMPLETED);
        file.setVectorizationStatus(vectorizationStatus);
        return file;
    }
}
