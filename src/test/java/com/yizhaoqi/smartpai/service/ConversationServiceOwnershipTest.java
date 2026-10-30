package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.ConversationSession;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.ConversationSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationServiceOwnershipTest {
    @Test
    void explicitConversationMustExistAndBelongToAuthenticatedUser() {
        ConversationSessionRepository repository = mock(ConversationSessionRepository.class);
        ConversationService service = new ConversationService();
        ReflectionTestUtils.setField(service, "sessionRepository", repository);
        User owner = new User();
        owner.setId(1L);
        ConversationSession session = new ConversationSession();
        session.setUser(owner);
        when(repository.findByConversationId("owned")).thenReturn(Optional.of(session));
        when(repository.findByConversationId("missing")).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> service.validateConversationOwner(1L, "owned"));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(CustomException.class,
                () -> service.validateConversationOwner(2L, "owned")).getStatus());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(CustomException.class,
                () -> service.validateConversationOwner(1L, "missing")).getStatus());
    }
}
