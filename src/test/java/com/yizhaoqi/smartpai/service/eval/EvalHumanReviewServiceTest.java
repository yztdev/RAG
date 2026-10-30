package com.yizhaoqi.smartpai.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.HybridSearchProperties;
import com.yizhaoqi.smartpai.config.RagEvalProperties;
import com.yizhaoqi.smartpai.exception.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvalHumanReviewServiceTest {

    @TempDir
    Path tempDir;

    private final RagEvalCaseRunner caseRunner = mock(RagEvalCaseRunner.class);
    private final RagEvalProperties properties = new RagEvalProperties();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private RagEvalService evalService;
    private EvalHumanReviewService reviewService;
    private String runId;

    @BeforeEach
    void setUp() {
        properties.setOutputDir(tempDir.resolve("reports").toString());
        properties.getHumanAgreement().setMinComparableCases(4);
        evalService = new RagEvalService(caseRunner, properties, new HybridSearchProperties(), null, null,
                objectMapper, new DirectExecutor());
        reviewService = new EvalHumanReviewService(evalService, properties, objectMapper);

        // 评委：c1 对、c2 对、c3 错、c4 对、c5 评委解析失败
        List<EvalCase> cases = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            EvalCase evalCase = new EvalCase("c" + i, "q" + i, List.of(), List.of(), "ref");
            cases.add(evalCase);
            Boolean correct = i == 5 ? null : i != 3;
            Integer score = i == 5 ? null : (i == 3 ? 2 : 5);
            when(caseRunner.run(org.mockito.ArgumentMatchers.eq(evalCase), anyString(), anyInt(), any())).thenReturn(
                    new EvalCaseResult("c" + i, "q" + i, "ref", List.of(), List.of(), List.of(), "HYBRID", List.of(), null, null, null,
                            null, null, "answer", score, correct, "", null, EvalDiagnosis.NOT_LABELED, i == 5, null, null, 1L));
        }
        runId = evalService.start(cases, "inline", 3, "calib", "7", "admin").runId();
    }

    @Test
    void submittingVerdictsPersistsSidecarAndFlagsRunSummary() throws Exception {
        EvalHumanReviewService.HumanReviewSummary summary = reviewService.submit(runId, List.of(
                new EvalHumanReviewService.HumanReviewInput("c1", true, 5, null),
                new EvalHumanReviewService.HumanReviewInput("c2", false, 2, "回答漏了关键前提"),
                new EvalHumanReviewService.HumanReviewInput("c3", true, 4, null),
                new EvalHumanReviewService.HumanReviewInput("c4", true, null, null),
                new EvalHumanReviewService.HumanReviewInput("c5", false, 1, "评委没给结论，人工补判")), "reviewer");

        JudgeHumanAgreement agreement = summary.agreement();
        assertEquals(5, agreement.reviewedCases());
        assertEquals(4, agreement.comparableCases());
        assertEquals(1, agreement.humanOnlyCases());
        assertEquals(0.5, agreement.agreementRate(), 1e-9);
        assertEquals(JudgeHumanAgreement.Status.NEEDS_RECALIBRATION, agreement.status());
        assertEquals(List.of("c2", "c3"), agreement.disagreementCaseIds());

        // 任务汇总上直接可见
        RagEvalService.RunStatus status = evalService.getStatus(runId).orElseThrow();
        assertEquals(JudgeHumanAgreement.Status.NEEDS_RECALIBRATION, status.humanAgreement().status());
        assertTrue(status.humanAgreement().warning().contains("重新校准"));

        // 旁路文件在报告旁边，报告本身不改
        Path report = Path.of(status.reportPath());
        Path sidecar = HumanReviewStore.sidecarPath(report);
        assertTrue(Files.exists(sidecar));
        assertTrue(sidecar.getFileName().toString().endsWith("_" + runId + ".human-reviews.json"));
        JsonNode stored = objectMapper.readTree(sidecar.toFile());
        assertEquals("回答漏了关键前提", stored.path("reviews").path("c2").path("note").asText());
        assertEquals("reviewer", stored.path("reviews").path("c2").path("reviewer").asText());
        assertTrue(objectMapper.readTree(report.toFile()).path("humanReview").isMissingNode());
    }

    @Test
    void resubmittingOverridesAndSurvivesRestart() {
        reviewService.submit(runId, List.of(new EvalHumanReviewService.HumanReviewInput("c2", false, null, null)), "a");
        reviewService.submit(runId, List.of(new EvalHumanReviewService.HumanReviewInput("c2", true, 5, null)), "b");

        RagEvalService restarted = new RagEvalService(caseRunner, properties, new HybridSearchProperties(), null, null,
                objectMapper, new DirectExecutor());
        EvalHumanReviewService.HumanReviewSummary summary =
                new EvalHumanReviewService(restarted, properties, objectMapper).get(runId);

        assertEquals(1, summary.reviews().size());
        assertTrue(summary.reviews().get(0).correct());
        assertEquals("b", summary.reviews().get(0).reviewer());
        assertEquals(JudgeHumanAgreement.Status.INSUFFICIENT_SAMPLE, summary.agreement().status());
    }

    @Test
    void validatesInput() {
        assertBadRequest(() -> reviewService.submit(runId, List.of(), "a"));
        assertBadRequest(() -> reviewService.submit(runId,
                List.of(new EvalHumanReviewService.HumanReviewInput("nope", true, null, null)), "a"));
        assertBadRequest(() -> reviewService.submit(runId,
                List.of(new EvalHumanReviewService.HumanReviewInput("c1", null, null, null)), "a"));
        assertBadRequest(() -> reviewService.submit(runId,
                List.of(new EvalHumanReviewService.HumanReviewInput("c1", true, 6, null)), "a"));
        assertBadRequest(() -> reviewService.submit(runId,
                List.of(new EvalHumanReviewService.HumanReviewInput("c1", true, null, "长".repeat(501))), "a"));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(CustomException.class,
                () -> reviewService.get("000000000000")).getStatus());
        assertNull(reviewService.attachmentFor(runId), "没有人工判定时报告不附带 humanReview");
    }

    private static void assertBadRequest(org.junit.jupiter.api.function.Executable executable) {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(CustomException.class, executable).getStatus());
    }

    private static final class DirectExecutor extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
