package com.yizhaoqi.smartpai.service.contextual;

import com.yizhaoqi.smartpai.client.resilience.ModelCallPriority;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.ContextualRetrievalProperties;
import com.yizhaoqi.smartpai.exception.RateLimitExceededException;
import com.yizhaoqi.smartpai.service.LlmProviderRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkContextGeneratorTest {

    private final LlmProviderRouter router = mock(LlmProviderRouter.class);
    private final ContextualRetrievalProperties properties = new ContextualRetrievalProperties();
    private ChunkContextGenerator generator;

    @BeforeEach
    void setUp() {
        properties.setRateLimitBackoffSeconds(0);
        properties.setMaxContextChars(20);
        generator = new ChunkContextGenerator(router, properties, new ObjectMapper());
    }

    @Test
    void sendsFixedSystemPromptThenWindowAndParsesContexts() throws Exception {
        when(router.completeReActTurn(eq("system-contextual"), anyList(), eq(List.of()), anyInt(), eq(ModelCallPriority.BACKGROUND)))
                .thenReturn(turn("```json\n[{\"id\": 2, \"context\": \"出自 ACME 二季度财报营收部分。\"},"
                        + " {\"id\": \"3\", \"context\": \"  毛利率\\n说明。 \"}, {\"id\": 9, \"context\": \"不是本批\"}]\n```"));

        Map<Integer, String> contexts = generator.generate("文档标题：ACME", Set.of(2, 3));

        assertEquals(Map.of(2, "出自 ACME 二季度财报营收部分。", 3, "毛利率 说明。"), contexts);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
        verify(router).completeReActTurn(eq("system-contextual"), messages.capture(), eq(List.of()), eq(1200), eq(ModelCallPriority.BACKGROUND));
        assertEquals(ChunkContextGenerator.SYSTEM_PROMPT, messages.getValue().get(0).get("content"),
                "system 提示词固定，便于命中前缀缓存");
        assertEquals("文档标题：ACME", messages.getValue().get(1).get("content"));
    }

    @Test
    void truncatesOverlongContext() throws Exception {
        when(router.completeReActTurn(eq("system-contextual"), anyList(), eq(List.of()), anyInt(), eq(ModelCallPriority.BACKGROUND)))
                .thenReturn(turn("[{\"id\": 1, \"context\": \"" + "长".repeat(50) + "\"}]"));

        assertEquals(20, generator.generate("w", Set.of(1)).get(1).length());
    }

    @Test
    void retriesOnGlobalRateLimitThenSucceeds() throws Exception {
        when(router.completeReActTurn(eq("system-contextual"), anyList(), eq(List.of()), anyInt(), eq(ModelCallPriority.BACKGROUND)))
                .thenThrow(new RateLimitExceededException("LLM 全网分钟 Token 预算已达上限", 10))
                .thenReturn(turn("[{\"id\": 1, \"context\": \"说明\"}]"));

        assertEquals(Map.of(1, "说明"), generator.generate("w", Set.of(1)));
        verify(router, times(2)).completeReActTurn(eq("system-contextual"), anyList(), eq(List.of()), anyInt(), eq(ModelCallPriority.BACKGROUND));
    }

    @Test
    void nonRateLimitErrorsAndGarbageOutputPropagateForCallerFallback() {
        when(router.completeReActTurn(eq("system-contextual"), anyList(), eq(List.of()), anyInt(), eq(ModelCallPriority.BACKGROUND)))
                .thenThrow(new RuntimeException("upstream 500"));
        assertThrows(RuntimeException.class, () -> generator.generate("w", Set.of(1)));
        verify(router, times(1)).completeReActTurn(eq("system-contextual"), anyList(), eq(List.of()), anyInt(), eq(ModelCallPriority.BACKGROUND));

        assertThrows(IllegalStateException.class, () -> generator.parse("抱歉，我无法完成", Set.of(1)));
    }

    private static LlmProviderRouter.ReActTurn turn(String content) {
        return new LlmProviderRouter.ReActTurn(content, List.of(), Map.of(), "stop", 1, 1);
    }
}
