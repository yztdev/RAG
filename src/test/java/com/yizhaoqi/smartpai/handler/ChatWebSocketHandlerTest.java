package com.yizhaoqi.smartpai.handler;

import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.service.ChatSessionRegistry;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.net.URI;
import static org.mockito.Mockito.*;

class ChatWebSocketHandlerTest {
    private final ChatHandler chat = mock(ChatHandler.class);
    private final JwtUtils jwt = mock(JwtUtils.class);
    private final ChatSessionRegistry registry = mock(ChatSessionRegistry.class);
    private final WebSocketSession socket = mock(WebSocketSession.class);
    private final ChatWebSocketHandler handler = new ChatWebSocketHandler(chat, jwt, registry);

    @BeforeEach
    void setup() {
        when(socket.getUri()).thenReturn(URI.create("ws://localhost/chat/test-token"));
        when(jwt.extractUserIdFromToken("test-token")).thenReturn("1");
    }

    @Test
    void structuredChatCarriesItsOwnConversationInsteadOfSharedCurrentConversation() {
        handler.handleTextMessage(socket, new TextMessage("{\"type\":\"chat\",\"message\":\"hello\",\"conversationId\":\"page-a\"}"));
        verify(chat).processMessage("1", "hello", socket, "page-a");
        verifyNoMoreInteractions(chat);
    }

    @Test
    void resumeUsesAuthenticatedIdentityWithoutStartingAnotherInference() {
        handler.handleTextMessage(socket, new TextMessage("{\"type\":\"resume\",\"generationId\":\"g1\",\"userId\":\"2\"}"));
        verify(registry).resumeGeneration("1", "g1", socket);
        verifyNoInteractions(chat);
    }

    @Test
    void legacyPlainTextRemainsSupported() {
        handler.handleTextMessage(socket, new TextMessage("hello"));
        verify(chat).processMessage("1", "hello", socket);
    }
}
