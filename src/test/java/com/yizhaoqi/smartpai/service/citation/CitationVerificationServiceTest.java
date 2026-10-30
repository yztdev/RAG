package com.yizhaoqi.smartpai.service.citation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.service.LlmProviderRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CitationVerificationServiceTest {

    private static final String ANSWER = "分片大小是 5MB(来源#1: upload.md)。合并在内存里完成(来源#2: merge.md)。上传支持断点续传(来源#1: upload.md)(来源#9: none.md)。";
    private static final Map<Integer, String> REFERENCES = Map.of(
            1, "分片上传：每个分片 5MB，支持断点续传。",
            2, "合并时先在磁盘上拼成临时文件，再上传到对象存储。"
    );

    private final AiProperties aiProperties = new AiProperties();
    private final LlmProviderRouter router = mock(LlmProviderRouter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private CitationVerificationService service;

    @BeforeEach
    void setUp() {
        aiProperties.getCitationVerification().setEnabled(true);
        service = new CitationVerificationService(aiProperties, router, objectMapper, Runnable::run);
    }

    @Test
    void marksUnsupportedCitationsAsSuspiciousInOneBatchedCall() throws Exception {
        when(router.completeReActTurn(eq("7"), anyList(), eq(List.of()), anyInt())).thenReturn(turn("""
                ```json
                [{"id":1,"supported":true,"reason":"片段写明5MB"},
                 {"id":2,"supported":false,"reason":"片段说在磁盘上合并"},
                 {"id":3,"supported":"true","reason":""}]
                ```"""));

        Map<Integer, CitationVerdict> verdicts = service.verify("7", ANSWER, REFERENCES);

        assertEquals(CitationVerdict.Status.SUPPORTED, verdicts.get(1).status());
        assertEquals(CitationVerdict.Status.SUSPICIOUS, verdicts.get(2).status());
        assertTrue(verdicts.get(2).reason().contains("片段说在磁盘上合并"));
        assertTrue(verdicts.get(2).reason().contains("合并在内存里完成"));
        assertEquals(2, verdicts.size(), "不存在的来源 #9 不送校验");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
        verify(router).completeReActTurn(eq("7"), messages.capture(), eq(List.of()), anyInt());
        String payload = (String) messages.getValue().get(1).get("content");
        List<?> items = objectMapper.readValue(payload, List.class);
        assertEquals(3, items.size(), "所有（句子, 片段）对合并成一次调用");
    }

    @Test
    void oneUnsupportedSentenceMakesTheReferenceSuspicious() {
        when(router.completeReActTurn(any(), anyList(), anyList(), anyInt())).thenReturn(turn("""
                [{"id":1,"supported":false,"reason":"未提及"},{"id":2,"supported":true},{"id":3,"supported":true}]"""));

        Map<Integer, CitationVerdict> verdicts = service.verify("7", ANSWER, REFERENCES);

        assertEquals(CitationVerdict.Status.SUSPICIOUS, verdicts.get(1).status(), "#1 的第二句被支撑也不能覆盖可疑结论");
    }

    @Test
    void disabledOrFailedVerificationReturnsNothing() {
        aiProperties.getCitationVerification().setEnabled(false);
        assertTrue(service.verify("7", ANSWER, REFERENCES).isEmpty());
        verify(router, never()).completeReActTurn(any(), anyList(), anyList(), anyInt());

        aiProperties.getCitationVerification().setEnabled(true);
        when(router.completeReActTurn(any(), anyList(), anyList(), anyInt())).thenThrow(new RuntimeException("quota"));
        assertTrue(service.verify("7", ANSWER, REFERENCES).isEmpty());
    }

    @Test
    void malformedJudgementIsTreatedAsUnverified() {
        when(router.completeReActTurn(any(), anyList(), anyList(), anyInt())).thenReturn(turn("我认为都支持"));

        assertTrue(service.verify("7", ANSWER, REFERENCES).isEmpty());
    }

    @Test
    void respectsMaxClaims() {
        aiProperties.getCitationVerification().setMaxClaims(1);
        List<CitationVerificationService.ClaimPair> pairs = service.buildPairs(
                CitationExtractor.extract(ANSWER, 300), REFERENCES, aiProperties.getCitationVerification());

        assertEquals(1, pairs.size());
    }

    private static LlmProviderRouter.ReActTurn turn(String content) {
        return new LlmProviderRouter.ReActTurn(content, List.of(), Map.of(), "stop", 10, 10);
    }
}
