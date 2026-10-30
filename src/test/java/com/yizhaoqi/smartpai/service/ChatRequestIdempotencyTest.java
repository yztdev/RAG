package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatRequestIdempotencyTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChatGenerationStateService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(values);
        service = new ChatGenerationStateService(redisTemplate, objectMapper);
    }

    @Test
    void firstRequestClaimsTheKey() {
        when(values.setIfAbsent(startsWith("chat:request:1:"), eq("gen-new"), any(Duration.class))).thenReturn(true);

        assertNull(service.claimRequest("1", "conv", "  什么是 RAG ", "gen-new"));
    }

    @Test
    void duplicateWhileStreamingReusesInFlightGeneration() throws Exception {
        when(values.setIfAbsent(anyString(), eq("gen-new"), any(Duration.class))).thenReturn(false);
        when(values.get(startsWith("chat:request:1:"))).thenReturn("gen-old");
        when(values.get("chat:generation:gen-old:meta")).thenReturn(meta("gen-old", "1", "STREAMING"));

        assertEquals("gen-old", service.claimRequest("1", "conv", "什么是 RAG", "gen-new"));
        verify(values, never()).set(startsWith("chat:request:"), eq("gen-new"), any(Duration.class));
    }

    @Test
    void finishedGenerationDoesNotBlockAskingAgain() throws Exception {
        when(values.setIfAbsent(anyString(), eq("gen-new"), any(Duration.class))).thenReturn(false);
        when(values.get(startsWith("chat:request:1:"))).thenReturn("gen-old");
        when(values.get("chat:generation:gen-old:meta")).thenReturn(meta("gen-old", "1", "COMPLETED"));

        assertNull(service.claimRequest("1", "conv", "什么是 RAG", "gen-new"));
        verify(values).set(startsWith("chat:request:1:"), eq("gen-new"), any(Duration.class));
    }

    private String meta(String generationId, String userId, String status) throws Exception {
        return objectMapper.writeValueAsString(new ChatGenerationStateService.GenerationMeta(
                generationId, userId, "conv", "什么是 RAG", ChatGenerationStateService.GenerationStatus.valueOf(status),
                "2026-10-06T10:00", "2026-10-06T10:00", null));
    }
}
