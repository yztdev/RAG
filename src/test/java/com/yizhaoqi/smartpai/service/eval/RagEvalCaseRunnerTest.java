package com.yizhaoqi.smartpai.service.eval;

import com.yizhaoqi.smartpai.client.resilience.ModelCallPriority;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.ParentContextProperties;
import com.yizhaoqi.smartpai.config.RagEvalProperties;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import com.yizhaoqi.smartpai.service.LlmProviderRouter;
import com.yizhaoqi.smartpai.service.search.ParentContextExpander;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagEvalCaseRunnerTest {

    private final HybridSearchService searchService = mock(HybridSearchService.class);
    private final LlmProviderRouter router = mock(LlmProviderRouter.class);
    private final RagEvalProperties properties = new RagEvalProperties();
    private final ParentContextProperties parentProperties = new ParentContextProperties();
    private final DocumentVectorRepository documentVectorRepository = mock(DocumentVectorRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HumanReviewSampler noSampling = new HumanReviewSampler(0d, new Random(1));
    private final List<List<Map<String, Object>>> llmCalls = new ArrayList<>();
    private final List<String> llmReplies = new ArrayList<>();
    /** 与 llmReplies 一一对应，为空时按 stop 处理 */
    private final List<String> llmFinishReasons = new ArrayList<>();
    private final List<Integer> llmMaxTokens = new ArrayList<>();
    private RagEvalCaseRunner runner;

    @BeforeEach
    void setUp() {
        parentProperties.setEnabled(false);
        runner = new RagEvalCaseRunner(searchService, router,
                new ParentContextExpander(parentProperties, documentVectorRepository), properties, objectMapper, Runnable::run);
        when(router.buildRagMessages(anyString(), anyString()))
                .thenAnswer(invocation -> List.of(Map.<String, Object>of("role", "user", "content", invocation.getArgument(0))));
        when(router.completeReActTurn(anyString(), anyList(), anyList(), anyInt(), eq(ModelCallPriority.BACKGROUND))).thenAnswer(invocation -> {
            List<Map<String, Object>> messages = invocation.getArgument(1);
            llmCalls.add(messages);
            llmMaxTokens.add(invocation.getArgument(3));
            String reply = llmReplies.remove(0);
            String finishReason = llmFinishReasons.isEmpty() ? "stop" : llmFinishReasons.remove(0);
            if (reply == null) {
                throw new RuntimeException("upstream 500");
            }
            return new LlmProviderRouter.ReActTurn(reply, List.of(), Map.of(), finishReason, 1, 1);
        });
    }

    @Test
    void retrievalHitAndCorrectAnswer() throws Exception {
        when(searchService.searchWithTrace("端口？", "7", 3)).thenReturn(trace(List.of(
                result("other", "x.pdf", "HYBRID"), result("m1", "config.pdf", "HYBRID"))));
        llmReplies.add("端口是 8080（来源#2: config.pdf）");
        llmReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}");

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling);

        assertEquals("HYBRID", result.retrievalMode());
        assertTrue(result.retrievalHit());
        assertEquals(2, result.firstRelevantRank());
        assertEquals(0.5, result.reciprocalRank(), 1e-9);
        assertTrue(result.retrieved().get(1).relevant());
        assertEquals("端口是 8080（来源#2: config.pdf）", result.answer());
        assertEquals(5, result.judgeScore());
        assertEquals(EvalDiagnosis.OK, result.diagnosis());
        assertFalse(result.needsHumanReview());

        // 生成和评委是两次独立调用；评委的输入作为 JSON 数据放在 user 消息里
        assertEquals(2, llmCalls.size());
        List<Map<String, Object>> judgeMessages = llmCalls.get(1);
        assertEquals(RagEvalCaseRunner.JUDGE_SYSTEM_PROMPT, judgeMessages.get(0).get("content"));
        JsonNode payload = objectMapper.readTree((String) judgeMessages.get(1).get("content"));
        assertEquals("端口？", payload.get("question").asText());
        assertEquals("8080", payload.get("reference_answer").asText());
        assertEquals(2, payload.get("evidence").size());
    }

    @Test
    void generationAndJudgeSeeParentBlockWhileRetrievalMetricsUseSmallChunk() throws Exception {
        parentProperties.setEnabled(true);
        when(searchService.searchWithTrace("端口？", "7", 3)).thenReturn(trace(List.of(result("m1", "config.pdf", "HYBRID"))));
        when(documentVectorRepository.findByFileMd5AndChunkIdBetweenOrderByChunkIdAsc("m1", 0, 2)).thenReturn(List.of(
                chunk("m1", 1, "端口配置为 8080"), chunk("m1", 2, "修改端口后需要重启服务。")));
        llmReplies.add("端口是 8080，修改后需要重启（来源#1: config.pdf）");
        llmReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}");

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling);

        ArgumentCaptor<String> context = ArgumentCaptor.forClass(String.class);
        verify(router).buildRagMessages(eq("端口？"), context.capture());
        assertTrue(context.getValue().contains("上下文（命中分块及前后相邻内容）：\n端口配置为 8080\n\n修改端口后需要重启服务。"));
        JsonNode payload = objectMapper.readTree((String) llmCalls.get(1).get(1).get("content"));
        assertTrue(payload.get("evidence").get(0).get("text").asText().contains("修改端口后需要重启服务"),
                "评委看到的证据与生成时一致");
        assertEquals(1, result.retrieved().get(0).chunkId(), "检索明细仍指向命中的小分块");
        assertTrue(result.retrievalHit());
    }

    @Test
    void malformedJudgeOutputAlwaysNeedsHumanReview() {
        when(searchService.searchWithTrace(anyString(), eq("7"), anyInt())).thenReturn(trace(List.of(result("m1", "config.pdf", "HYBRID_RRF"))));
        llmReplies.add("端口是 8080");
        llmReplies.add("我给 5 分");

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling);

        assertEquals("HYBRID_RRF", result.retrievalMode());
        assertEquals(EvalDiagnosis.JUDGE_FAILED, result.diagnosis());
        assertTrue(result.needsHumanReview());
        assertEquals(HumanReviewSampler.REASON_JUDGE_FAILED, result.reviewReason());
        assertNull(result.judgeScore());
        assertEquals("我给 5 分", result.judgeRawOutput());
    }

    @Test
    void retrievalMissIsDiagnosedAsRetrievalProblem() {
        when(searchService.searchWithTrace(anyString(), eq("7"), anyInt())).thenReturn(trace(List.of(result("other", "x.pdf", "TEXT_ONLY"))));
        llmReplies.add("资料中没有相关说明");
        llmReplies.add("{\"score\": 1, \"correct\": false, \"reason\": \"没答出来\"}");

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling);

        assertFalse(result.retrievalHit());
        assertEquals(0d, result.reciprocalRank(), 1e-9);
        assertEquals("TEXT_ONLY", result.retrievalMode());
        assertEquals(EvalDiagnosis.RETRIEVAL_MISS, result.diagnosis());
    }

    @Test
    void retrievalMissJudgedCorrectAlwaysNeedsHumanReview() {
        when(searchService.searchWithTrace(anyString(), eq("7"), anyInt())).thenReturn(trace(List.of(result("other", "x.pdf", "HYBRID"))));
        llmReplies.add("端口是 8080");
        llmReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}");

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling);

        assertEquals(EvalDiagnosis.MISS_BUT_CORRECT, result.diagnosis());
        assertTrue(result.needsHumanReview());
        assertEquals(HumanReviewSampler.REASON_MISS_BUT_CORRECT, result.reviewReason());
    }

    @Test
    void generationFailureSkipsJudge() {
        when(searchService.searchWithTrace(anyString(), eq("7"), anyInt())).thenReturn(trace(List.of(result("m1", "config.pdf", "HYBRID"))));
        llmReplies.add(null);

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling);

        assertEquals(EvalDiagnosis.GENERATION_FAILED, result.diagnosis());
        assertEquals(1, llmCalls.size());
        assertTrue(result.error().contains("upstream 500"));
        assertFalse(result.needsHumanReview());
    }

    @Test
    void judgeTruncatedByReasoningIsRetriedWithDoubledBudget() {
        when(searchService.searchWithTrace(anyString(), eq("7"), anyInt())).thenReturn(trace(List.of(result("m1", "config.pdf", "HYBRID"))));
        llmReplies.addAll(List.of("端口是 8080", "", "{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}"));
        llmFinishReasons.addAll(List.of("stop", "length", "stop"));

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling);

        assertEquals(EvalDiagnosis.OK, result.diagnosis());
        assertEquals(List.of(4000, 2000, 4000), llmMaxTokens);
    }

    @Test
    void emptyAnswerAfterRetryFailsWithFinishReasonAndCappedBudget() {
        properties.setMaxRetryTokens(6000);
        when(searchService.searchWithTrace(anyString(), eq("7"), anyInt())).thenReturn(trace(List.of(result("m1", "config.pdf", "HYBRID"))));
        llmReplies.addAll(List.of("", " "));
        llmFinishReasons.addAll(List.of("length", "length"));

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling);

        assertEquals(EvalDiagnosis.GENERATION_FAILED, result.diagnosis());
        assertTrue(result.error().contains("finish_reason=length"));
        assertEquals(List.of(4000, 6000), llmMaxTokens, "重试预算翻倍但不超过 max-retry-tokens");
    }

    @Test
    void emptyOutputNotCausedByLengthRetriesWithSameBudget() {
        when(searchService.searchWithTrace(anyString(), eq("7"), anyInt())).thenReturn(trace(List.of(result("m1", "config.pdf", "HYBRID"))));
        llmReplies.addAll(List.of("端口是 8080", "", ""));

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling);

        assertEquals(EvalDiagnosis.JUDGE_FAILED, result.diagnosis());
        assertTrue(result.error().contains("评委输出为空（finish_reason=stop）"));
        assertEquals(List.of(4000, 2000, 2000), llmMaxTokens);
    }

    @Test
    void emptyRetrievalUsesNoResultPromptAndModeNone() {
        when(searchService.searchWithTrace(anyString(), eq("7"), anyInt())).thenReturn(trace(List.of()));
        llmReplies.add("暂无相关信息");
        llmReplies.add("{\"score\": 1, \"correct\": false}");

        EvalCaseResult result = runner.run(evalCase(List.of()), "7", 3, noSampling);

        assertEquals("NONE", result.retrievalMode());
        assertNull(result.retrievalHit());
        assertEquals(EvalDiagnosis.NOT_LABELED, result.diagnosis());
    }

    @Test
    void retrievalExceptionIsRecorded() {
        when(searchService.searchWithTrace(anyString(), eq("7"), anyInt())).thenThrow(new IllegalStateException("es down"));

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling);

        assertEquals(EvalDiagnosis.RETRIEVAL_FAILED, result.diagnosis());
        assertTrue(llmCalls.isEmpty());
    }

    @Test
    void retrievalOnlyRecordsStageMetricsWithoutCallingTheModel() {
        SearchResult relevant = result("m1", "config.pdf", "HYBRID");
        SearchResult noise = result("other", "x.pdf", "HYBRID");
        // 候选池里第 3 名才是相关分块，最终 topK 把它排到了第 2
        HybridSearchService.SearchTrace trace = new HybridSearchService.SearchTrace(
                List.of(noise, relevant), List.of(noise, noise, relevant), List.of(relevant), List.of(noise), "HYBRID", 1);
        when(searchService.searchWithTrace(anyString(), eq("7"), anyInt())).thenReturn(trace);

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling, true);

        assertTrue(llmCalls.isEmpty(), "只跑检索时不调用模型");
        assertEquals(2, result.firstRelevantRank());
        EvalCaseResult.Stages stages = result.stages();
        assertTrue(stages.candidateHit());
        assertEquals(1, stages.relevantInCandidates());
        assertEquals(1d / (Math.log(3) / Math.log(2)), stages.ndcgAtK(), 1e-9, "相关分块排第 2：DCG=1/log2(3)，IDCG=1");
        assertTrue(stages.bm25Hit());
        assertEquals(1, stages.bm25FirstRank());
        assertTrue(!stages.knnHit());
        assertEquals(1, stages.thresholdDroppedInTopK());
        assertEquals(3, stages.candidates().size());
        assertTrue(stages.candidates().get(2).relevant());
        assertEquals(0.5, stages.precisionAtK(), 1e-9, "返回 2 条、1 条相关");
        assertEquals(1, stages.irrelevantInTopK());
        assertEquals(2, stages.returnedCount());
    }

    private static EvalCase evalCase(List<String> md5s) {
        return new EvalCase("c1", "端口？", md5s, List.of(), "8080");
    }

    private static HybridSearchService.SearchTrace trace(List<SearchResult> results) {
        String mode = results.isEmpty() ? null : results.get(0).getRetrievalMode();
        return new HybridSearchService.SearchTrace(results, results, List.of(), List.of(), mode, 0);
    }

    private static SearchResult result(String md5, String fileName, String mode) {
        return new SearchResult(md5, 1, "端口配置为 8080", 0.9, "7", "dept", false, fileName, null, null, mode, null);
    }

    private static DocumentVector chunk(String md5, int chunkId, String text) {
        DocumentVector vector = new DocumentVector();
        vector.setFileMd5(md5);
        vector.setChunkId(chunkId);
        vector.setTextContent(text);
        return vector;
    }
}
