package com.yizhaoqi.smartpai.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.ContextualRetrievalProperties;
import com.yizhaoqi.smartpai.config.HybridSearchProperties;
import com.yizhaoqi.smartpai.config.ParentContextProperties;
import com.yizhaoqi.smartpai.config.RagEvalProperties;
import com.yizhaoqi.smartpai.exception.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagEvalServiceTest {

    @TempDir
    Path tempDir;

    private final RagEvalCaseRunner caseRunner = mock(RagEvalCaseRunner.class);
    private final RagEvalProperties properties = new RagEvalProperties();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private RagEvalService service;

    @BeforeEach
    void setUp() {
        properties.setOutputDir(tempDir.resolve("reports").toString());
        properties.setDatasetDir(tempDir.resolve("datasets").toString());
        service = new RagEvalService(caseRunner, properties, new HybridSearchProperties(), new ParentContextProperties(),
                new ContextualRetrievalProperties(), objectMapper, new DirectExecutor());
    }

    @Test
    void runWritesJsonReportWithLabelAndSummary() throws Exception {
        EvalCase evalCase = new EvalCase("c1", "q", List.of("m1"), List.of(), "ref");
        when(caseRunner.run(eq(evalCase), eq("7"), anyInt(), any())).thenReturn(new EvalCaseResult(
                "c1", "q", "ref", List.of("m1"), List.of(), List.of(), "HYBRID", List.of(), true, true, 1, 1.0, 1.0,
                "answer", 4, true, "ok", null, EvalDiagnosis.OK, false, null, null, 12L));

        RagEvalService.RunStatus submitted = service.start(List.of(evalCase), "classpath:eval/sample-eval.jsonl", 3,
                "chunk 512/重叠 64", "7", "admin");

        RagEvalService.RunStatus status = service.getStatus(submitted.runId()).orElseThrow();
        assertEquals(RagEvalService.RunState.SUCCEEDED, status.state());
        assertEquals("chunk_512_重叠_64", status.label());
        assertEquals(1, status.completedCases());
        assertEquals(1.0, status.summary().recallAtK());
        assertTrue(Files.exists(Path.of(status.reportPath())));
        assertTrue(status.reportPath().contains("chunk_512_重叠_64"));

        JsonNode report = service.readReport(submitted.runId()).orElseThrow();
        assertEquals("chunk_512_重叠_64", report.get("label").asText());
        assertEquals(3, report.get("topK").asInt());
        assertEquals(1.0, report.get("summary").get("accuracy").asDouble(), 1e-9);
        assertEquals("OK", report.get("cases").get(0).get("diagnosis").asText());
        assertTrue(report.get("diagnosisGuide").has("PROMPT_OR_MODEL"));
        assertTrue(report.get("config").get("parentContextEnabled").asBoolean(), "报告记录父子检索开关，便于前后对比");
        assertFalse(report.get("config").get("contextualRetrievalEnabled").asBoolean());
    }

    @Test
    void reportPathIsFoundOnDiskAfterRunStateIsGone() throws Exception {
        EvalCase evalCase = new EvalCase("c1", "q", List.of(), List.of(), "ref");
        when(caseRunner.run(eq(evalCase), eq("7"), anyInt(), any())).thenReturn(new EvalCaseResult(
                "c1", "q", "ref", List.of(), List.of(), List.of(), "HYBRID", List.of(), null, null, null, null, null,
                "answer", 4, true, "ok", null, EvalDiagnosis.NOT_LABELED, false, null, null, 12L));
        String runId = service.start(List.of(evalCase), "inline", 3, "l", "7", "admin").runId();

        // 模拟重启：新的服务实例内存里没有任务状态
        RagEvalService restarted = new RagEvalService(caseRunner, properties, new HybridSearchProperties(), null, null,
                objectMapper, new DirectExecutor());

        assertTrue(restarted.findReportPath(runId).isPresent());
        assertTrue(restarted.readReport(runId).isPresent());
        assertTrue(restarted.findReportPath("../etc").isEmpty(), "runId 只允许字母数字");
    }

    @Test
    void rejectsInvalidTopK() {
        EvalCase evalCase = new EvalCase("c1", "q", List.of(), List.of(), "ref");
        CustomException error = assertThrows(CustomException.class,
                () -> service.start(List.of(evalCase), "inline", 0, null, "7", "admin"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
    }

    @Test
    void loadsBundledClasspathDataset() {
        assertTrue(service.loadDataset("classpath:eval/sample-eval.jsonl").size() >= 3);
    }

    @Test
    void datasetPathCannotEscapeDatasetDir() throws Exception {
        Files.createDirectories(tempDir.resolve("datasets"));
        Files.writeString(tempDir.resolve("secret.jsonl"), "{\"question\": \"q\", \"referenceAnswer\": \"a\"}\n");

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(CustomException.class, () -> service.loadDataset("../secret.jsonl")).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(CustomException.class, () -> service.loadDataset(tempDir.resolve("secret.jsonl").toString())).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(CustomException.class, () -> service.loadDataset("classpath:eval/../application.yml")).getStatus());
    }

    @Test
    void loadsDatasetFromDatasetDir() throws Exception {
        Files.createDirectories(tempDir.resolve("datasets/v1"));
        Files.writeString(tempDir.resolve("datasets/v1/cases.jsonl"), "{\"question\": \"q\", \"referenceAnswer\": \"a\"}\n");

        assertEquals(1, service.loadDataset("v1/cases.jsonl").size());
    }

    @Test
    void labelIsSanitizedForFileName() {
        assertEquals("default", RagEvalService.sanitizeLabel(null));
        assertEquals("_etc_passwd", RagEvalService.sanitizeLabel("../etc/passwd"));
    }

    /** 同步执行，便于断言任务结束后的状态 */
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
