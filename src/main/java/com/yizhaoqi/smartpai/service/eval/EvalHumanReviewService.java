package com.yizhaoqi.smartpai.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.RagEvalProperties;
import com.yizhaoqi.smartpai.exception.CustomException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评委校准：管理员对评测用例录入人工判定（correct + 可选 1-5 分 + 备注），
 * 与评委结论对比算一致率和 Cohen's kappa，一致率低于阈值时在评测汇总上告警。
 * 人工判定保存在报告旁边的 JSON 文件里（见 {@link HumanReviewStore}），重启后仍然可用。
 */
@Service
public class EvalHumanReviewService {

    private static final Logger logger = LoggerFactory.getLogger(EvalHumanReviewService.class);
    static final int MAX_NOTE_CHARS = 500;

    private final RagEvalService evalService;
    private final RagEvalProperties properties;
    private final ObjectMapper objectMapper;
    private final HumanReviewStore store;

    public EvalHumanReviewService(RagEvalService evalService, RagEvalProperties properties, ObjectMapper objectMapper) {
        this.evalService = evalService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.store = new HumanReviewStore(objectMapper);
    }

    /**
     * 录入或覆盖人工判定（同一 caseId 重复提交以最后一次为准）
     */
    public synchronized HumanReviewSummary submit(String runId, List<HumanReviewInput> inputs, String reviewer) {
        Path reportPath = requireReport(runId);
        List<JudgeHumanAgreement.JudgeLabel> labels = readJudgeLabels(reportPath);
        validate(inputs, labels);
        try {
            Map<String, HumanVerdict> reviews = store.load(reportPath);
            Instant now = Instant.now();
            for (HumanReviewInput input : inputs) {
                String note = input.note() == null || input.note().isBlank() ? null : input.note().trim();
                reviews.put(input.caseId(), new HumanVerdict(input.caseId(), input.correct(), input.score(), note, reviewer, now));
            }
            JudgeHumanAgreement agreement = JudgeHumanAgreement.compute(labels, reviews, properties.getHumanAgreement());
            store.save(reportPath, runId, reviews, agreement);
            evalService.recordHumanAgreement(runId, agreement);
            logger.info("评测人工判定已保存: runId={}, reviewer={}, submitted={}, reviewed={}, agreement={}, kappa={}, status={}",
                    runId, reviewer, inputs.size(), agreement.reviewedCases(), agreement.agreementRate(),
                    agreement.cohensKappa(), agreement.status());
            if (agreement.status() == JudgeHumanAgreement.Status.NEEDS_RECALIBRATION) {
                logger.warn("评委需要重新校准: runId={}, {}", runId, agreement.warning());
            }
            return new HumanReviewSummary(runId, List.copyOf(reviews.values()), agreement);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomException("保存人工判定失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 当前人工判定和一致率（按当前阈值配置重新计算）
     */
    public HumanReviewSummary get(String runId) {
        Path reportPath = requireReport(runId);
        try {
            Map<String, HumanVerdict> reviews = store.load(reportPath);
            JudgeHumanAgreement agreement = JudgeHumanAgreement.compute(
                    readJudgeLabels(reportPath), reviews, properties.getHumanAgreement());
            evalService.recordHumanAgreement(runId, agreement);
            return new HumanReviewSummary(runId, List.copyOf(reviews.values()), agreement);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomException("读取人工判定失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private Path requireReport(String runId) {
        return evalService.findReportPath(runId)
                .orElseThrow(() -> new CustomException("评测报告不存在或任务尚未完成: " + runId, HttpStatus.NOT_FOUND));
    }

    private List<JudgeHumanAgreement.JudgeLabel> readJudgeLabels(Path reportPath) {
        JsonNode report;
        try {
            report = objectMapper.readTree(reportPath.toFile());
        } catch (Exception e) {
            throw new CustomException("读取评测报告失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
        List<JudgeHumanAgreement.JudgeLabel> labels = new ArrayList<>();
        for (JsonNode node : report.path("cases")) {
            JsonNode correct = node.path("judgeCorrect");
            JsonNode score = node.path("judgeScore");
            labels.add(new JudgeHumanAgreement.JudgeLabel(
                    node.path("caseId").asText(),
                    correct.isBoolean() ? correct.asBoolean() : null,
                    score.isInt() ? score.asInt() : null));
        }
        return labels;
    }

    private void validate(List<HumanReviewInput> inputs, List<JudgeHumanAgreement.JudgeLabel> labels) {
        if (inputs == null || inputs.isEmpty()) {
            throw new CustomException("reviews 不能为空", HttpStatus.BAD_REQUEST);
        }
        if (inputs.size() > Math.max(properties.getMaxCases(), 1)) {
            throw new CustomException("单次最多提交 " + properties.getMaxCases() + " 条人工判定", HttpStatus.BAD_REQUEST);
        }
        Set<String> knownCases = new LinkedHashSet<>();
        labels.forEach(label -> knownCases.add(label.caseId()));
        List<String> unknown = new ArrayList<>();
        for (HumanReviewInput input : inputs) {
            if (input == null || input.caseId() == null || input.caseId().isBlank()) {
                throw new CustomException("caseId 不能为空", HttpStatus.BAD_REQUEST);
            }
            if (input.correct() == null) {
                throw new CustomException("用例 " + input.caseId() + " 缺少 correct（true / false）", HttpStatus.BAD_REQUEST);
            }
            if (input.score() != null && (input.score() < 1 || input.score() > 5)) {
                throw new CustomException("用例 " + input.caseId() + " 的 score 必须是 1-5 的整数", HttpStatus.BAD_REQUEST);
            }
            if (input.note() != null && input.note().length() > MAX_NOTE_CHARS) {
                throw new CustomException("用例 " + input.caseId() + " 的 note 不能超过 " + MAX_NOTE_CHARS + " 字", HttpStatus.BAD_REQUEST);
            }
            if (!knownCases.contains(input.caseId())) {
                unknown.add(input.caseId());
            }
        }
        if (!unknown.isEmpty()) {
            throw new CustomException("报告中不存在这些用例: " + unknown, HttpStatus.BAD_REQUEST);
        }
    }

    public record HumanReviewInput(String caseId, Boolean correct, Integer score, String note) {
    }

    public record HumanReviewSummary(String runId, List<HumanVerdict> reviews, JudgeHumanAgreement agreement) {
    }

    /**
     * 供报告接口附带展示：没有人工判定时返回 null
     */
    public Map<String, Object> attachmentFor(String runId) {
        HumanReviewSummary summary = get(runId);
        if (summary.reviews().isEmpty()) {
            return null;
        }
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("reviews", summary.reviews());
        attachment.put("agreement", summary.agreement());
        return attachment;
    }
}
