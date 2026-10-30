package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ChatSessionRegistryTest {
    private final ChatSessionRegistry registry = new ChatSessionRegistry(new ObjectMapper());
    private final Map<String, Object> chunk = Map.of("generationId", "g1", "chunk", "answer");

    private WebSocketSession openSocket() {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        return socket;
    }

    @Test
    void openingAnotherTabBeforeOrDuringGenerationDoesNotStealFrames() throws Exception {
        WebSocketSession origin = openSocket();
        WebSocketSession other = openSocket();
        registry.registerSession("1", origin);
        registry.registerSession("1", other);
        registry.bindGeneration("1", "g1", origin);
        registry.sendJsonToUser("1", chunk);
        registry.registerSession("1", openSocket());
        registry.sendJsonToUser("1", chunk);
        verify(origin, times(2)).sendMessage(any());
        verify(other, never()).sendMessage(any());
    }

    @Test
    void closedOriginDoesNotFallBackToUnrelatedTab() throws Exception {
        WebSocketSession origin = openSocket();
        WebSocketSession other = openSocket();
        registry.bindGeneration("1", "g1", origin);
        registry.registerSession("1", other);
        when(origin.isOpen()).thenReturn(false);
        registry.unregisterSession("1", origin);
        registry.sendJsonToUser("1", chunk);
        verify(other, never()).sendMessage(any());
        assertSame(other, registry.getCurrentSession("1"));
    }

    @Test
    void ResumeSubscribesWithoutTakingStreamAwayFromOrigin() throws Exception {
        WebSocketSession origin = openSocket();
        WebSocketSession resumed = openSocket();
        registry.bindGeneration("1", "g1", origin);
        registry.resumeGeneration("1", "g1", resumed);
        registry.sendJsonToUser("1", chunk);
        verify(origin).sendMessage(any());
        verify(resumed).sendMessage(any());
        registry.unregisterSession("1", origin);
        registry.sendJsonToUser("1", chunk);
        verify(origin, times(1)).sendMessage(any());
        verify(resumed, times(2)).sendMessage(any());
    }

    @Test
    void differentUserCannotResumeOrReceiveGeneration() throws Exception {
        WebSocketSession owner = openSocket();
        WebSocketSession stranger = openSocket();
        registry.bindGeneration("1", "g1", owner);
        registry.resumeGeneration("2", "g1", stranger);
        registry.sendJsonToUser("2", chunk);
        registry.sendJsonToUser("1", chunk);
        verify(owner).sendMessage(any());
        verify(stranger, never()).sendMessage(any());
    }

    @Test
    void completedGenerationCannotBeResurrectedOrRerouted() throws Exception {
        WebSocketSession socket = openSocket();
        registry.registerSession("1", socket);
        registry.bindGeneration("1", "g1", socket);
        registry.releaseGeneration("g1");
        registry.resumeGeneration("1", "g1", socket);
        registry.sendJsonToUser("1", chunk);
        verify(socket, never()).sendMessage(any());
    }
}
