package com.yizhaoqi.smartpai.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.ContextualRetrievalProperties;
import com.yizhaoqi.smartpai.config.HybridSearchProperties;
import com.yizhaoqi.smartpai.config.ParentContextProperties;
import com.yizhaoqi.smartpai.config.RagEvalProperties;
import com.yizhaoqi.smartpai.exception.CustomException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 离线 RAG 评测任务：异步逐条跑用例，汇总 Recall@K / MRR / 评委平均分 / 准确率和问题归因，报告写成 JSON 文件。
 * 同一时间只允许一个评测任务；同一数据集改动分块前后各跑一次，用 label 区分即可对比。
 */
@Service
public class RagEvalService {

    private static final Logger logger = LoggerFactory.getLogger(RagEvalService.class);
    private static final String CLASSPATH_PREFIX = "classpath:eval/";
    private static final Pattern SAFE_CLASSPATH_NAME = Pattern.compile("[\\w\\-]+(/[\\w\\-]+)*\\.jsonl");
    private static final Pattern UNSAFE_LABEL_CHARS = Pattern.compile("[^\\w\\-\\u4e00-\\u9fa5]+");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
    private static final int MAX_KEPT_RUNS = 20;
    private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9]{1,32}");

    private final RagEvalCaseRunner caseRunner;
    private final RagEvalProperties properties;
    private final HybridSearchProperties searchProperties;
    private final ParentContextProperties parentContextProperties;
    private final ContextualRetrievalProperties contextualProperties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final Map<String, RunProgress> runs = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile RunProgress latest;

    @Autowired
    public RagEvalService(RagEvalCaseRunner caseRunner,
                          RagEvalProperties properties,
                          HybridSearchProperties searchProperties,
                          ParentContextProperties parentContextProperties,
                          ContextualRetrievalProperties contextualProperties,
                          ObjectMapper objectMapper) {
        this(caseRunner, properties, searchProperties, parentContextProperties, contextualProperties, objectMapper,
                Executors.newSingleThreadExecutor(r -> new Thread(r, "rag-eval")));
    }

    RagEvalService(RagEvalCaseRunner caseRunner,
                   RagEvalProperties properties,
                   HybridSearchProperties searchProperties,
                   ParentContextProperties parentContextProperties,
                   ContextualRetrievalProperties contextualProperties,
                   ObjectMapper objectMapper,
                   ExecutorService executor) {
        this.caseRunner = caseRunner;
        this.properties = properties;
        this.searchProperties = searchProperties;
        this.parentContextProperties = parentContextProperties;
        this.contextualProperties = contextualProperties;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public List<EvalCase> loadDataset(String datasetPath) {
        if (datasetPath == null || datasetPath.isBlank()) {
            throw new CustomException("datasetPath 不能为空", HttpStatus.BAD_REQUEST);
        }
        String path = datasetPath.trim();
        try {
            if (path.startsWith(CLASSPATH_PREFIX)) {
                String name = path.substring(CLASSPATH_PREFIX.length());
                if (!SAFE_CLASSPATH_NAME.matcher(name).matches()) {
                    throw new CustomException("非法的 classpath 数据集名称: " + name, HttpStatus.BAD_REQUEST);
                }
                ClassPathResource resource = new ClassPathResource("eval/" + name);
                if (!resource.exists()) {
                    throw new CustomException("数据集不存在: " + path, HttpStatus.NOT_FOUND);
                }
                try (InputStream in = resource.getInputStream()) {
                    return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            }
            Path base = Paths.get(properties.getDatasetDir()).toAbsolutePath().normalize();
            Path file = base.resolve(path).normalize();
            if (!file.startsWith(base)) {
                throw new CustomException("数据集只能放在 eval.dataset-dir 目录下: " + base, HttpStatus.BAD_REQUEST);
            }
            if (!Files.isRegularFile(file)) {
                throw new CustomException("数据集不存在: " + file, HttpStatus.NOT_FOUND);
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return parse(reader);
            }
        } catch (CustomException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new CustomException("数据集格式错误: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            throw new CustomException("读取数据集失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public List<EvalCase> parseInline(String jsonl) {
        try {
            return parse(new StringReader(jsonl == null ? "" : jsonl));
        } catch (IllegalArgumentException e) {
            throw new CustomException("数据集格式错误: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            throw new CustomException("读取数据集失败: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    private List<EvalCase> parse(Reader reader) throws Exception {
        return EvalDatasetParser.parse(reader, objectMapper, Math.max(properties.getMaxCases(), 1));
    }

    /**
     * 提交评测任务（异步执行）
     *
     * @param searchUserId 检索以该用户身份执行（权限过滤与线上一致）
     */
    public synchronized RunStatus start(List<EvalCase> cases, String datasetSource, Integer topK, String label,
                                        String searchUserId, String operator) {
        return start(cases, datasetSource, topK, label, searchUserId, operator, false);
    }

    /**
     * @param retrievalOnly 只跑检索、只统计检索指标（不生成、不评委打分）
     */
    public synchronized RunStatus start(List<EvalCase> cases, String datasetSource, Integer topK, String label,
                                        String searchUserId, String operator, boolean retrievalOnly) {
        RunProgress running = latest;
        if (running != null && running.state == RunState.RUNNING) {
            throw new CustomException("已有评测任务在运行: " + running.runId, HttpStatus.CONFLICT);
        }
        if (cases == null || cases.isEmpty()) {
            throw new CustomException("数据集为空", HttpStatus.BAD_REQUEST);
        }
        int k = topK == null ? properties.getDefaultTopK() : topK;
        if (k < 1 || k > properties.getMaxTopK()) {
            throw new CustomException("topK 需在 1 到 " + properties.getMaxTopK() + " 之间", HttpStatus.BAD_REQUEST);
        }

        String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        RunProgress progress = new RunProgress(runId, sanitizeLabel(label), datasetSource, k, cases.size(), operator);
        progress.retrievalOnly = retrievalOnly;
        latest = progress;
        remember(progress);
        HumanReviewSampler sampler = HumanReviewSampler.of(properties.getHumanReviewSampleRate(), properties.getRandomSeed());
        try {
            executor.execute(() -> runAll(progress, cases, searchUserId, sampler));
        } catch (Exception e) {
            progress.fail(e);
            throw new CustomException("评测任务提交失败: " + e.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        }
        logger.info("RAG 评测任务已提交: runId={}, label={}, dataset={}, cases={}, topK={}, operator={}",
                runId, progress.label, datasetSource, cases.size(), k, operator);
        return progress.snapshot();
    }

    public Optional<RunStatus> getStatus(String runId) {
        return Optional.ofNullable(runs.get(runId)).map(RunProgress::snapshot);
    }

    public Optional<RunStatus> getLatestStatus() {
        return Optional.ofNullable(latest).map(RunProgress::snapshot);
    }

    public List<RunStatus> listStatuses() {
        synchronized (runs) {
            List<RunStatus> statuses = new ArrayList<>();
            runs.values().forEach(progress -> statuses.add(progress.snapshot()));
            Collections.reverse(statuses);
            return statuses;
        }
    }

    public Optional<JsonNode> readReport(String runId) {
        Optional<Path> reportPath = findReportPath(runId);
        if (reportPath.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(reportPath.get().toFile()));
        } catch (Exception e) {
            throw new CustomException("读取评测报告失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 报告文件路径。任务状态只在内存里保留最近 20 个，重启后按文件名（..._{runId}.json）在输出目录里找
     */
    public Optional<Path> findReportPath(String runId) {
        if (runId == null || !SAFE_RUN_ID.matcher(runId).matches()) {
            return Optional.empty();
        }
        RunProgress progress = runs.get(runId);
        if (progress != null) {
            return Optional.ofNullable(progress.reportPath).map(Paths::get);
        }
        Path dir = Paths.get(properties.getOutputDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        String suffix = "_" + runId + ".json";
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> file.getFileName().toString().endsWith(suffix)).findFirst();
        } catch (Exception e) {
            throw new CustomException("查找评测报告失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 人工判定更新后，把评委与人工一致率挂到内存里的任务状态上，列表和详情接口直接可见
     */
    void recordHumanAgreement(String runId, JudgeHumanAgreement agreement) {
        RunProgress progress = runs.get(runId);
        if (progress != null) {
            progress.humanAgreement = agreement;
        }
    }

    void runAll(RunProgress progress, List<EvalCase> cases, String searchUserId, HumanReviewSampler sampler) {
        try {
            List<EvalCaseResult> results = new ArrayList<>(cases.size());
            for (EvalCase evalCase : cases) {
                results.add(progress.retrievalOnly
                        ? caseRunner.run(evalCase, searchUserId, progress.topK, sampler, true)
                        : caseRunner.run(evalCase, searchUserId, progress.topK, sampler));
                progress.completedCases = results.size();
            }
            EvalReport report = new EvalReport(progress.runId, progress.label, progress.datasetSource, progress.topK,
                    progress.startedAt, Instant.now(), configSnapshot(progress.topK, progress.retrievalOnly),
                    EvalReportAggregator.summarize(results), EvalReportAggregator.diagnosisGuide(), List.copyOf(results));
            Path reportPath = writeReport(report);
            progress.succeed(report.summary(), reportPath.toString());
            logger.info("RAG 评测完成: runId={}, label={}, report={}, summary={}",
                    progress.runId, progress.label, reportPath, report.summary());
        } catch (Exception e) {
            progress.fail(e);
            logger.error("RAG 评测失败: runId={}", progress.runId, e);
        }
    }

    Path writeReport(EvalReport report) throws Exception {
        Path dir = Paths.get(properties.getOutputDir()).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        String fileName = FILE_TIME.format(report.startedAt()) + "_" + report.label() + "_" + report.runId() + ".json";
        Path file = dir.resolve(fileName);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
        return file;
    }

    private Map<String, Object> configSnapshot(int topK, boolean retrievalOnly) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("topK", topK);
        config.put("retrievalOnly", retrievalOnly);
        config.put("llm", caseRunner.primaryLlmDescription());
        config.put("generationMaxTokens", properties.getGenerationMaxTokens());
        config.put("judgeMaxTokens", properties.getJudgeMaxTokens());
        config.put("rerankEnabled", searchProperties.getRerank().isEnabled());
        config.put("rerankModel", searchProperties.getRerank().getModel());
        config.put("rerankScoreThreshold", searchProperties.getRerank().getScoreThreshold());
        config.put("rrfK", searchProperties.getRrfK());
        config.put("rrfScoreThreshold", searchProperties.getRrfScoreThreshold());
        config.put("recallMultiplier", searchProperties.getRecallMultiplier());
        config.put("rerankMaxCandidates", searchProperties.getRerank().getMaxCandidates());
        config.put("keywordFileNameBoost", searchProperties.getKeyword().getFileNameBoost());
        config.put("keywordMinimumShouldMatch", searchProperties.getKeyword().getMinimumShouldMatch());
        config.put("keywordPhraseBoost", searchProperties.getKeyword().getPhraseBoost());
        if (parentContextProperties != null) {
            config.put("parentContextEnabled", parentContextProperties.isEnabled());
            config.put("parentContextNeighbourChunks", parentContextProperties.getNeighbourChunks());
            config.put("parentContextMaxCharsPerHit", parentContextProperties.getMaxCharsPerHit());
        }
        if (contextualProperties != null) {
            config.put("contextualRetrievalEnabled", contextualProperties.isEnabled());
        }
        config.put("humanReviewSampleRate", properties.getHumanReviewSampleRate());
        config.put("randomSeed", properties.getRandomSeed());
        return config;
    }

    private void remember(RunProgress progress) {
        synchronized (runs) {
            runs.put(progress.runId, progress);
            while (runs.size() > MAX_KEPT_RUNS) {
                String oldest = runs.keySet().iterator().next();
                runs.remove(oldest);
            }
        }
    }

    static String sanitizeLabel(String label) {
        if (label == null || label.isBlank()) {
            return "default";
        }
        String cleaned = UNSAFE_LABEL_CHARS.matcher(label.trim()).replaceAll("_");
        return cleaned.length() <= 40 ? cleaned : cleaned.substring(0, 40);
    }

    public enum RunState {
        RUNNING, SUCCEEDED, FAILED
    }

    public record RunStatus(
            String runId,
            String label,
            String datasetSource,
            int topK,
            RunState state,
            int totalCases,
            int completedCases,
            String operator,
            String reportPath,
            EvalReport.EvalSummary summary,
            /* 评委与人工一致率，录入人工判定后才有；低于阈值时 status = NEEDS_RECALIBRATION 并带 warning */
            JudgeHumanAgreement humanAgreement,
            String message,
            Instant startedAt,
            Instant finishedAt
    ) {
    }

    static final class RunProgress {
        private final String runId;
        private final String label;
        private final String datasetSource;
        private final int topK;
        private final int totalCases;
        private final String operator;
        private final Instant startedAt = Instant.now();
        private volatile RunState state = RunState.RUNNING;
        private volatile int completedCases;
        private volatile String reportPath;
        private volatile EvalReport.EvalSummary summary;
        private volatile JudgeHumanAgreement humanAgreement;
        private volatile String message;
        private volatile Instant finishedAt;
        private volatile boolean retrievalOnly;

        RunProgress(String runId, String label, String datasetSource, int topK, int totalCases, String operator) {
            this.runId = runId;
            this.label = label;
            this.datasetSource = datasetSource;
            this.topK = topK;
            this.totalCases = totalCases;
            this.operator = operator;
        }

        void succeed(EvalReport.EvalSummary summary, String reportPath) {
            this.summary = summary;
            this.reportPath = reportPath;
            this.finishedAt = Instant.now();
            this.state = RunState.SUCCEEDED;
        }

        void fail(Exception e) {
            this.message = e.getMessage();
            this.finishedAt = Instant.now();
            this.state = RunState.FAILED;
        }

        RunStatus snapshot() {
            return new RunStatus(runId, label, datasetSource, topK, state, totalCases, completedCases, operator,
                    reportPath, summary, humanAgreement, message, startedAt, finishedAt);
        }
    }
}
