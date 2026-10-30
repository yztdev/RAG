package com.yizhaoqi.smartpai.service.conflict;

import com.yizhaoqi.smartpai.client.resilience.ModelCallPriority;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.DocumentConflictProperties;
import com.yizhaoqi.smartpai.service.LlmProviderRouter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentConflictDetectionServiceTest {

    private final DocumentConflictProperties properties = new DocumentConflictProperties();
    private final ConflictCandidateFinder finder = mock(ConflictCandidateFinder.class);
    private final LlmProviderRouter router = mock(LlmProviderRouter.class);
    private final ConflictLlmConfirmer confirmer = new ConflictLlmConfirmer(properties, router, new ObjectMapper());
    private final DocumentConflictService conflictService = mock(DocumentConflictService.class);

    @Test
    void disabledDetectionDoesNothing() throws Exception {
        properties.setEnabled(false);
        service(Runnable::run).onDocumentVectorized(new DocumentVectorizedEvent("md5"));

        verify(finder, never()).findCandidates(anyString());
    }

    @Test
    void scanFailureIsSwallowedSoIngestionIsNotAffected() throws Exception {
        when(finder.findCandidates("md5")).thenThrow(new RuntimeException("es down"));

        assertDoesNotThrow(() -> service(Runnable::run).onDocumentVectorized(new DocumentVectorizedEvent("md5")));
        verify(conflictService, never()).recordCandidates(anyList());
    }

    @Test
    void executorRejectionIsSwallowed() {
        assertDoesNotThrow(() -> service(task -> {
            throw new RejectedExecutionException("full");
        }).onDocumentVectorized(new DocumentVectorizedEvent("md5")));
    }

    @Test
    void candidatesAreRecordedWithoutLlmWhenConfirmDisabled() throws Exception {
        List<ConflictCandidate> candidates = List.of(candidate());
        when(finder.findCandidates("md5")).thenReturn(candidates);

        service(Runnable::run).onDocumentVectorized(new DocumentVectorizedEvent("md5"));

        verify(conflictService).recordCandidates(candidates);
        verify(router, never()).completeReActTurn(anyString(), anyList(), anyList(), anyInt(), eq(ModelCallPriority.BACKGROUND));
    }

    @Test
    void llmConfirmDropsRejectedCandidatesAndKeepsUnansweredOnes() throws Exception {
        properties.getLlmConfirm().setEnabled(true);
        List<ConflictCandidate> candidates = List.of(candidate(), candidate(), candidate());
        when(router.completeReActTurn(anyString(), anyList(), anyList(), anyInt(), eq(ModelCallPriority.BACKGROUND))).thenReturn(turn("""
                ```json
                [{"id": 1, "conflict": true, "reason": "同一端口取值不同"}, {"id": 2, "conflict": false, "reason": "不同服务"}]
                ```"""));

        List<ConflictCandidate> confirmed = confirmer.confirm(candidates);

        assertEquals(2, confirmed.size());
        assertEquals("同一端口取值不同", confirmed.get(0).llmReason());
        assertSame(candidates.get(2), confirmed.get(1));
    }

    @Test
    void llmConfirmFailureKeepsRuleResults() {
        properties.getLlmConfirm().setEnabled(true);
        List<ConflictCandidate> candidates = List.of(candidate());
        when(router.completeReActTurn(anyString(), anyList(), anyList(), anyInt(), eq(ModelCallPriority.BACKGROUND))).thenReturn(turn("我认为这不是冲突"));

        assertSame(candidates, confirmer.confirm(candidates));
    }

    private DocumentConflictDetectionService service(java.util.concurrent.Executor executor) {
        return new DocumentConflictDetectionService(properties, finder, confirmer, conflictService, executor);
    }

    private static LlmProviderRouter.ReActTurn turn(String content) {
        return new LlmProviderRouter.ReActTurn(content, List.of(), Map.of(), "stop", 10, 10);
    }

    private static ConflictCandidate candidate() {
        return new ConflictCandidate(
                new ConflictCandidate.ChunkRef("new", 1, "端口 8080"),
                new ConflictCandidate.ChunkRef("old", 2, "端口 9090"),
                0.97, 1.0, Set.of("number:8080"), Set.of("number:9090"), null);
    }
}
