package com.yizhaoqi.smartpai.service.reliability;

import com.yizhaoqi.smartpai.config.FileProcessingReliabilityProperties;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.UploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileProcessingRecoveryJobTest {

    private final FileUploadRepository repository = mock(FileUploadRepository.class);
    private final FileProcessingOutboxService outboxService = mock(FileProcessingOutboxService.class);
    private final UploadService uploadService = mock(UploadService.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final FileProcessingReliabilityProperties properties = new FileProcessingReliabilityProperties();
    private final DocumentStatusNotifier statusNotifier = mock(DocumentStatusNotifier.class);
    private FileProcessingRecoveryJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
        job = new FileProcessingRecoveryJob(repository, outboxService, uploadService, properties, transactionTemplate, statusNotifier);
    }

    @Test
    void staleTaskUnderRetryLimitIsRequeuedThroughOutbox() {
        FileUpload stale = stale(1);
        when(repository.findStaleVectorizations(any(), any())).thenReturn(List.of(stale));
        when(repository.requeueStaleVectorization(eq(7L), eq(stale.getHeartbeatAt()), any())).thenReturn(1);

        assertEquals(1, job.recoverStaleVectorizations());

        ArgumentCaptor<FileProcessingTask> task = ArgumentCaptor.forClass(FileProcessingTask.class);
        verify(outboxService).append(task.capture());
        assertEquals("md5", task.getValue().getFileMd5());
        // 重新投递不带预签名 URL，消费者直接读 MinIO 合并对象
        assertNull(task.getValue().getFilePath());
        verify(statusNotifier).notifyChanged(7L);
    }

    @Test
    void legacyTaskWithoutClaimTimeIsFailedInsteadOfRequeued() {
        FileUpload legacy = stale(0);
        legacy.setProcessingStartedAt(null);
        legacy.setHeartbeatAt(null);
        when(repository.findStaleVectorizations(any(), any())).thenReturn(List.of(legacy));
        when(repository.failStaleVectorization(eq(7L), eq(null), eq(FileProcessingRecoveryJob.LEGACY_TASK_MESSAGE), any())).thenReturn(1);

        assertEquals(1, job.recoverStaleVectorizations());

        verify(repository, never()).requeueStaleVectorization(any(), any(), any());
        verify(outboxService, never()).append(any());
        verify(statusNotifier).notifyChanged(7L);
    }

    @Test
    void requeueLosingTheRaceDoesNotPublish() {
        FileUpload stale = stale(0);
        when(repository.findStaleVectorizations(any(), any())).thenReturn(List.of(stale));
        when(repository.requeueStaleVectorization(eq(7L), eq(stale.getHeartbeatAt()), any())).thenReturn(0);

        assertEquals(0, job.recoverStaleVectorizations());
        verify(outboxService, never()).append(any());
    }

    @Test
    void taskOverRetryLimitIsMarkedFailedInsteadOfRequeued() {
        FileUpload stale = stale(properties.getMaxRetries());
        when(repository.findStaleVectorizations(any(), any())).thenReturn(List.of(stale));
        when(repository.failStaleVectorization(eq(7L), eq(stale.getHeartbeatAt()), anyString(), any())).thenReturn(1);

        assertEquals(1, job.recoverStaleVectorizations());
        verify(repository, never()).requeueStaleVectorization(any(), any(), any());
        verify(outboxService, never()).append(any());
    }

    @Test
    void abandonedUploadChunksAreCleanedOnlyAfterRecordDeleteSucceeds() {
        FileUpload kept = stale(0);
        kept.setStatus(FileUpload.STATUS_UPLOADING);
        FileUpload abandoned = stale(0);
        abandoned.setId(8L);
        abandoned.setFileMd5("md5-2");
        abandoned.setStatus(FileUpload.STATUS_UPLOADING);
        when(repository.findStaleByUploadStatus(eq(FileUpload.STATUS_UPLOADING), any(), any())).thenReturn(List.of(kept, abandoned));
        when(repository.deleteAbandonedUpload(7L, FileUpload.STATUS_UPLOADING, kept.getHeartbeatAt())).thenReturn(0);
        when(repository.deleteAbandonedUpload(8L, FileUpload.STATUS_UPLOADING, abandoned.getHeartbeatAt())).thenReturn(1);

        assertEquals(1, job.cleanupAbandonedUploads());
        verify(uploadService).cleanupAbandonedChunks("md5-2", "1");
        verify(uploadService, never()).cleanupAbandonedChunks("md5", "1");
    }

    private FileUpload stale(int retryCount) {
        FileUpload file = new FileUpload();
        file.setId(7L);
        file.setFileMd5("md5");
        file.setFileName("a.pdf");
        file.setUserId("1");
        file.setStatus(FileUpload.STATUS_COMPLETED);
        file.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_PROCESSING);
        file.setHeartbeatAt(LocalDateTime.now().minusHours(1));
        file.setProcessingStartedAt(LocalDateTime.now().minusHours(2));
        file.setRetryCount(retryCount);
        return file;
    }
}
