package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.exception.InsufficientBalanceException;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.service.DocumentService;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.UploadService;
import com.yizhaoqi.smartpai.service.VectorizationService;
import com.yizhaoqi.smartpai.service.reliability.VectorizationLease;
import com.yizhaoqi.smartpai.service.reliability.VectorizationLeaseLostException;
import com.yizhaoqi.smartpai.service.reliability.VectorizationLeaseService;
import io.minio.GetObjectResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FileProcessingConsumerIdempotencyTest {

    private final ParseService parseService = mock(ParseService.class);
    private final VectorizationService vectorizationService = mock(VectorizationService.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final VectorizationLeaseService leaseService = mock(VectorizationLeaseService.class);
    private final UploadService uploadService = mock(UploadService.class);
    private final VectorizationLease lease = new VectorizationLease(1L, "md5", LocalDateTime.now());
    private FileProcessingConsumer consumer;

    @BeforeEach
    void setUp() throws Exception {
        consumer = new FileProcessingConsumer(parseService, vectorizationService, documentService, leaseService, uploadService);
        when(uploadService.getMergedFileStream("md5")).thenReturn(mock(GetObjectResponse.class));
    }

    @Test
    void duplicateDeliveryOfCompletedFileIsSkippedWithoutParsing() {
        when(leaseService.claim(any())).thenReturn(Optional.empty());

        consumer.processTask(task());

        verifyNoInteractions(parseService, vectorizationService, documentService, uploadService);
    }

    @Test
    void oldChunksAndIndexAreClearedBeforeReparsing() throws Exception {
        when(leaseService.claim(any())).thenReturn(Optional.of(lease));
        VectorizationService.VectorizationUsageResult result = new VectorizationService.VectorizationUsageResult(10, 2, "m");
        when(vectorizationService.vectorizeWithUsage(eq("md5"), eq("1"), eq("TEAM"), eq(false), eq("1"), any(Runnable.class)))
                .thenReturn(result);
        when(leaseService.complete(lease, result)).thenReturn(true);

        consumer.processTask(task());

        InOrder inOrder = inOrder(documentService, parseService, vectorizationService, leaseService);
        inOrder.verify(documentService).clearIndexedData("md5");
        inOrder.verify(parseService).parseAndSave(eq("md5"), any(), eq("1"), eq("TEAM"), eq(false));
        inOrder.verify(leaseService).heartbeat(lease);
        inOrder.verify(vectorizationService).vectorizeWithUsage(eq("md5"), eq("1"), eq("TEAM"), eq(false), eq("1"), any(Runnable.class));
        inOrder.verify(leaseService).complete(lease, result);
    }

    @Test
    void lostLeaseStopsQuietlyWithoutMarkingFailure() throws Exception {
        when(leaseService.claim(any())).thenReturn(Optional.of(lease));
        doThrow(new VectorizationLeaseLostException("md5")).when(leaseService).heartbeat(lease);

        assertDoesNotThrow(() -> consumer.processTask(task()));

        verify(leaseService, never()).fail(any(), any());
        verify(vectorizationService, never()).vectorizeWithUsage(anyString(), anyString(), anyString(), anyBoolean(), anyString(), any(Runnable.class));
    }

    @Test
    void processingFailureMarksFailedAndRethrowsForKafkaRetry() throws Exception {
        when(leaseService.claim(any())).thenReturn(Optional.of(lease));
        doThrow(new RuntimeException("es down")).when(documentService).clearIndexedData("md5");

        assertThrows(RuntimeException.class, () -> consumer.processTask(task()));

        verify(leaseService).fail(eq(lease), any(RuntimeException.class));
        verifyNoInteractions(parseService);
    }

    @Test
    void insufficientBalanceFailsWithoutKafkaRetry() throws Exception {
        when(leaseService.claim(any())).thenReturn(Optional.of(lease));
        when(vectorizationService.vectorizeWithUsage(anyString(), anyString(), anyString(), anyBoolean(), anyString(), any(Runnable.class)))
                .thenThrow(new RuntimeException("向量化失败", new RuntimeException("向量生成失败",
                        new InsufficientBalanceException("Embedding Token 余额不足"))));

        // 不抛异常：Kafka 不会重试，也不会进死信队列
        assertDoesNotThrow(() -> consumer.processTask(task()));
        verify(leaseService).fail(eq(lease), any(RuntimeException.class));
    }

    private FileProcessingTask task() {
        return new FileProcessingTask("md5", "https://expired.example.com/merged/md5", "a.pdf", "1", "TEAM", false,
                FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS, "1");
    }
}
