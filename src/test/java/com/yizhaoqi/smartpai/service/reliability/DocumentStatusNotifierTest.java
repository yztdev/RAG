package com.yizhaoqi.smartpai.service.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.ChatSessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentStatusNotifierTest {

    private final FileUploadRepository repository = mock(FileUploadRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatSessionRegistry registry = new ChatSessionRegistry(objectMapper);
    private final DocumentStatusNotifier notifier = new DocumentStatusNotifier(repository, redis, registry, objectMapper);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesLatestRowOnlyAfterCommit() throws Exception {
        when(repository.findById(7L)).thenReturn(Optional.of(file()));
        TransactionSynchronizationManager.initSynchronization();

        notifier.notifyChanged(7L);
        verify(redis, never()).convertAndSend(anyString(), anyString());

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq(DocumentStatusNotifier.CHANNEL), message.capture());
        Map<?, ?> event = objectMapper.readValue(message.getValue(), Map.class);
        assertEquals("5", event.get("userId"));
        Map<?, ?> payload = (Map<?, ?>) event.get("payload");
        assertEquals("document_status", payload.get("type"));
        assertEquals("COMPLETED", payload.get("vectorizationStatus"));
        assertEquals(2, payload.get("actualChunkCount"));
    }

    @Test
    void channelMessageIsPushedToEveryOpenPageOfTheOwner() throws Exception {
        WebSocketSession tabA = openSession("a");
        WebSocketSession tabB = openSession("b");
        WebSocketSession otherUser = openSession("c");
        registry.registerSession("5", tabA);
        registry.registerSession("5", tabB);
        registry.registerSession("6", otherUser);

        String body = objectMapper.writeValueAsString(Map.of("userId", "5",
                "payload", DocumentStatusNotifier.toPayload(file())));
        notifier.onMessage(new DefaultMessage(DocumentStatusNotifier.CHANNEL.getBytes(), body.getBytes(StandardCharsets.UTF_8)), null);

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(tabA).sendMessage(sent.capture());
        assertTrue(sent.getValue().getPayload().contains("\"type\":\"document_status\""));
        verify(tabB, times(1)).sendMessage(any());
        verify(otherUser, never()).sendMessage(any());

        // 关掉一个页面后只推给剩下的
        registry.unregisterSession("5", tabA);
        notifier.onMessage(new DefaultMessage(DocumentStatusNotifier.CHANNEL.getBytes(), body.getBytes(StandardCharsets.UTF_8)), null);
        verify(tabA, times(1)).sendMessage(any());
        verify(tabB, times(2)).sendMessage(any());
    }

    private WebSocketSession openSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn(id);
        return session;
    }

    private FileUpload file() {
        FileUpload file = new FileUpload();
        file.setId(7L);
        file.setFileMd5("md5");
        file.setFileName("a.md");
        file.setUserId("5");
        file.setStatus(FileUpload.STATUS_COMPLETED);
        file.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_COMPLETED);
        file.setActualChunkCount(2);
        file.setActualEmbeddingTokens(519L);
        return file;
    }
}
