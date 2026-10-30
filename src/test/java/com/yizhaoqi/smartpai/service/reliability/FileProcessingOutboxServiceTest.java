package com.yizhaoqi.smartpai.service.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.FileProcessingReliabilityProperties;
import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.model.FileProcessingOutbox;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.repository.FileProcessingOutboxRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileProcessingOutboxServiceTest {

    private final FileProcessingOutboxRepository outboxRepository = mock(FileProcessingOutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final FileProcessingReliabilityProperties properties = new FileProcessingReliabilityProperties();
    private final FileProcessingOutboxService service = new FileProcessingOutboxService(
            outboxRepository, mock(FileUploadRepository.class), kafkaTemplate, mock(KafkaConfig.class),
            new ObjectMapper(), properties, Runnable::run, mock(DocumentStatusNotifier.class));

    @Test
    void unclaimedMessageIsNotSentTwice() throws Exception {
        FileProcessingOutbox outbox = pending();
        when(outboxRepository.claim(eq(1L), eq(outbox.getNextAttemptAt()), any())).thenReturn(0);

        assertFalse(service.dispatch(outbox));
        verify(kafkaTemplate, never()).executeInTransaction(any());
    }

    @Test
    void sendSuccessMarksSent() throws Exception {
        FileProcessingOutbox outbox = pending();
        when(outboxRepository.claim(eq(1L), eq(outbox.getNextAttemptAt()), any())).thenReturn(1);
        when(kafkaTemplate.executeInTransaction(any())).thenReturn(null);

        assertTrue(service.dispatch(outbox));
        verify(outboxRepository).markSent(eq(1L), any());
    }

    @Test
    void sendFailureSchedulesRetryWithBackoff() throws Exception {
        FileProcessingOutbox outbox = pending();
        when(outboxRepository.claim(eq(1L), eq(outbox.getNextAttemptAt()), any())).thenReturn(1);
        when(kafkaTemplate.executeInTransaction(any())).thenThrow(new IllegalStateException("broker down"));

        LocalDateTime before = LocalDateTime.now();
        assertFalse(service.dispatch(outbox));
        verify(outboxRepository, never()).markSent(anyLong(), any());
        verify(outboxRepository).markAttemptFailed(eq(1L), org.mockito.ArgumentMatchers.argThat(next -> next.isAfter(before)),
                eq("broker down"));
    }

    @Test
    void backoffGrowsAndIsCapped() {
        for (int attempt = 1; attempt <= 20; attempt++) {
            long seconds = service.backoffSeconds(attempt);
            assertTrue(seconds >= 1 && seconds <= properties.getOutbox().getMaxBackoffSeconds(), "attempt " + attempt + " -> " + seconds);
        }
        assertTrue(service.backoffSeconds(1) <= 5);
        assertTrue(service.backoffSeconds(20) >= properties.getOutbox().getMaxBackoffSeconds() / 2);
    }

    private FileProcessingOutbox pending() throws Exception {
        FileProcessingOutbox outbox = new FileProcessingOutbox();
        outbox.setId(1L);
        outbox.setFileMd5("md5");
        outbox.setTaskType(FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS);
        outbox.setPayload(new ObjectMapper().writeValueAsString(
                new FileProcessingTask("md5", null, "a.pdf", "1", "TEAM", false, FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS, "1")));
        outbox.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        return outbox;
    }
}
