package com.yizhaoqi.smartpai.service.reliability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.ChatSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文档向量化状态推送。
 * <p>
 * 状态变化的地方（投递、认领、完成、失败、卡住恢复）调用 {@link #notifyChanged}，事务提交后读最新一行发到 Redis 频道；
 * 每个实例都订阅这个频道，再通过 WebSocket 推给文件上传者在本实例上打开的所有页面。
 * 消费者和用户的 WebSocket 可能不在同一个实例上，所以要经过 Redis 广播一次。推送失败只记日志，前端有低频查询兜底
 */
@Component
public class DocumentStatusNotifier implements MessageListener {

    public static final String CHANNEL = "paismart:document-status";
    public static final String EVENT_TYPE = "document_status";

    private static final Logger logger = LoggerFactory.getLogger(DocumentStatusNotifier.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final FileUploadRepository fileUploadRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ChatSessionRegistry chatSessionRegistry;
    private final ObjectMapper objectMapper;

    public DocumentStatusNotifier(FileUploadRepository fileUploadRepository,
                                  StringRedisTemplate stringRedisTemplate,
                                  ChatSessionRegistry chatSessionRegistry,
                                  ObjectMapper objectMapper) {
        this.fileUploadRepository = fileUploadRepository;
        this.stringRedisTemplate = stringRedisTemplate;
        this.chatSessionRegistry = chatSessionRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * 在事务里调用时等提交后再推送，保证前端收到通知时查得到新状态
     */
    public void notifyChanged(Long fileUploadId) {
        if (fileUploadId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(fileUploadId);
                }
            });
            return;
        }
        publish(fileUploadId);
    }

    private void publish(Long fileUploadId) {
        try {
            fileUploadRepository.findById(fileUploadId).ifPresent(file -> {
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("userId", file.getUserId());
                message.put("payload", toPayload(file));
                try {
                    stringRedisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(message));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (Exception e) {
            logger.warn("发布文档状态变化失败，前端会通过定时查询兜底: fileUploadId={}, error={}", fileUploadId, e.getMessage());
        }
    }

    /**
     * Redis 频道消息：推给本实例上该用户的所有 WebSocket 连接
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            Map<String, Object> event = objectMapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), MAP_TYPE);
            Object userId = event.get("userId");
            Object payload = event.get("payload");
            if (userId == null || !(payload instanceof Map<?, ?> body)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) body;
            chatSessionRegistry.sendJsonToAllUserSessions(String.valueOf(userId), typed);
        } catch (Exception e) {
            logger.warn("推送文档状态变化失败: {}", e.getMessage());
        }
    }

    static Map<String, Object> toPayload(FileUpload file) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", EVENT_TYPE);
        payload.put("fileMd5", file.getFileMd5());
        payload.put("fileName", file.getFileName());
        payload.put("status", file.getStatus());
        payload.put("vectorizationStatus", file.getVectorizationStatus());
        payload.put("vectorizationErrorMessage", file.getVectorizationErrorMessage());
        payload.put("actualEmbeddingTokens", file.getActualEmbeddingTokens());
        payload.put("actualChunkCount", file.getActualChunkCount());
        payload.put("timestamp", System.currentTimeMillis());
        return payload;
    }
}
