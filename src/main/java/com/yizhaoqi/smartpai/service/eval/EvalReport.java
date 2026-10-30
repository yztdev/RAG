package com.yizhaoqi.smartpai.service.eval;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 评测报告。label 用来区分同一数据集的多次评测（例如调整分块前后各跑一次）。
 */
public record EvalReport(
        String runId,
        String label,
        String datasetSource,
        int topK,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Object> config,
        EvalSummary summary,
        Map<String, String> diagnosisGuide,
        List<EvalCaseResult> cases
) {

    /**
     * 汇总指标。没有可计算的样本时对应指标为 null（例如没有任何用例标注期望文档时 recallAtK 为 null）。
     */
    public record EvalSummary(
            int totalCases,
            int labeledCases,
            int retrievalHits,
            Double recallAtK,
            Double mrr,
            /* 只按文件判断的 Recall@K；evidenceLabeledCases 为 0 时与 recallAtK 相同 */
            Double docRecallAtK,
            /* 标注了 expectedEvidence、按分块判断命中的用例数 */
            int evidenceLabeledCases,
            Double meanExpectedDocCoverage,
            int judgedCases,
            int correctCases,
            Double meanJudgeScore,
            Double accuracy,
            int needsHumanReviewCount,
            int failedCases,
            Map<String, Long> retrievalModeCounts,
            Map<String, Long> diagnosisCounts,
            /* 分阶段检索指标，旧报告没有这一项 */
            StageSummary stages
    ) {

        public EvalSummary(int totalCases, int labeledCases, int retrievalHits, Double recallAtK, Double mrr,
                           Double docRecallAtK, int evidenceLabeledCases, Double meanExpectedDocCoverage, int judgedCases,
                           int correctCases, Double meanJudgeScore, Double accuracy, int needsHumanReviewCount,
                           int failedCases, Map<String, Long> retrievalModeCounts, Map<String, Long> diagnosisCounts) {
            this(totalCases, labeledCases, retrievalHits, recallAtK, mrr, docRecallAtK, evidenceLabeledCases,
                    meanExpectedDocCoverage, judgedCases, correctCases, meanJudgeScore, accuracy, needsHumanReviewCount,
                    failedCases, retrievalModeCounts, diagnosisCounts, null);
        }
    }

    /**
     * 分阶段检索汇总，只在标注了期望文档的用例上计算
     *
     * @param candidateRecall           召回阶段：RRF 候选（rerank 输入，约 50 条）里有相关分块的比例
     * @param meanNdcgAtK               精排阶段：候选里有相关分块的用例上，最终 topK 的平均 NDCG
     * @param ndcgCases                 参与 NDCG 平均的用例数
     * @param bm25Recall                BM25 这一路前 50 条的命中率
     * @param knnRecall                 KNN 这一路前 50 条的命中率
     * @param thresholdDroppedCases     前 topK 名里有结果因低于阈值被丢掉的用例数（为 0 说明阈值只过滤了 topK 之外的结果）
     * @param thresholdDroppedResults   前 topK 名里因低于阈值被丢掉的结果总条数
     * @param meanPrecisionAtK          有返回结果的标注用例上，返回结果中相关的比例（分母是实际返回条数）
     * @param meanIrrelevantInTopK      标注用例平均每题返回几条不相关结果
     * @param unlabeledCases            无答案（未标注期望文档）用例数
     * @param unlabeledMeanReturned     无答案用例平均返回条数，越少越好
     */
    public record StageSummary(int stagedCases,
                               Double candidateRecall,
                               Double meanNdcgAtK,
                               int ndcgCases,
                               Double bm25Recall,
                               Double knnRecall,
                               int thresholdDroppedCases,
                               int thresholdDroppedResults,
                               Double meanPrecisionAtK,
                               Double meanIrrelevantInTopK,
                               int unlabeledCases,
                               Double unlabeledMeanReturned) {
    }
}
