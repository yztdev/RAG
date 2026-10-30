package com.yizhaoqi.smartpai.service.eval;

import java.util.List;

/**
 * 单条用例的评测明细
 */
public record EvalCaseResult(
        String caseId,
        String question,
        String referenceAnswer,
        List<String> expectedFileMd5s,
        List<String> expectedFileNames,
        List<String> expectedEvidence,
        /* 检索 */
        String retrievalMode,
        List<RetrievedChunk> retrieved,
        /* 标注了 expectedEvidence 时按分块判断，否则按文件判断 */
        Boolean retrievalHit,
        /* 只按文件判断的命中，用来和分块级命中对比 */
        Boolean docHit,
        Integer firstRelevantRank,
        Double reciprocalRank,
        Double expectedDocCoverage,
        /* 生成 */
        String answer,
        /* 评委 */
        Integer judgeScore,
        Boolean judgeCorrect,
        String judgeReason,
        String judgeRawOutput,
        /* 结论 */
        EvalDiagnosis diagnosis,
        boolean needsHumanReview,
        String reviewReason,
        String error,
        long latencyMs,
        /* 分阶段检索指标，旧报告没有这一项 */
        Stages stages
) {

    public EvalCaseResult(String caseId, String question, String referenceAnswer, List<String> expectedFileMd5s,
                          List<String> expectedFileNames, List<String> expectedEvidence, String retrievalMode,
                          List<RetrievedChunk> retrieved, Boolean retrievalHit, Boolean docHit, Integer firstRelevantRank,
                          Double reciprocalRank, Double expectedDocCoverage, String answer, Integer judgeScore,
                          Boolean judgeCorrect, String judgeReason, String judgeRawOutput, EvalDiagnosis diagnosis,
                          boolean needsHumanReview, String reviewReason, String error, long latencyMs) {
        this(caseId, question, referenceAnswer, expectedFileMd5s, expectedFileNames, expectedEvidence, retrievalMode,
                retrieved, retrievalHit, docHit, firstRelevantRank, reciprocalRank, expectedDocCoverage, answer, judgeScore,
                judgeCorrect, judgeReason, judgeRawOutput, diagnosis, needsHumanReview, reviewReason, error, latencyMs, null);
    }

    /**
     * @param score    出口分数（换算到 0 到 1）；候选列表里是换算后的 RRF 分
     * @param rawScore 换算前的原始分，候选列表里就是 RRF 原始分，可离线模拟不同 RRF 阈值
     */
    public record RetrievedChunk(int rank, String fileMd5, String fileName, Integer chunkId, Double score, Double rawScore,
                                 boolean relevant) {

        public RetrievedChunk(int rank, String fileMd5, String fileName, Integer chunkId, Double score, boolean relevant) {
            this(rank, fileMd5, fileName, chunkId, score, null, relevant);
        }
    }

    /**
     * 分阶段检索指标：召回阶段看 RRF 候选（rerank 的输入）里有没有相关分块，精排阶段看最终 topK 的排序质量。
     * 只在标注了期望文档的用例上有值
     *
     * @param candidateHit           召回阶段命中：RRF 候选里有任一相关分块（Recall@候选数）
     * @param relevantInCandidates   候选里相关分块的条数，也是 NDCG 理想排序的相关条数
     * @param ndcgAtK                精排阶段：最终 topK 相对候选池理想排序的 NDCG（二元相关）；候选里没有相关分块时为 null
     * @param bm25Hit                BM25 这一路前 50 条里是否有相关分块
     * @param knnHit                 KNN 这一路前 50 条里是否有相关分块
     * @param thresholdDroppedInTopK 排在前 topK 名、却因为分数低于阈值被丢掉的条数
     * @param precisionAtK           最终返回结果里相关的比例，分母是实际返回条数（阈值少返回噪声时会上升）；没有返回结果时为 null
     * @param irrelevantInTopK       最终返回结果里不相关的条数（无答案用例里所有返回都算不相关）
     * @param returnedCount          最终返回条数
     * @param candidates             RRF 候选明细（带 RRF 原始分），用于离线分析阈值
     */
    public record Stages(Boolean candidateHit,
                         int candidateCount,
                         Integer relevantInCandidates,
                         Double ndcgAtK,
                         Boolean bm25Hit,
                         Integer bm25FirstRank,
                         Boolean knnHit,
                         Integer knnFirstRank,
                         int thresholdDroppedInTopK,
                         Double precisionAtK,
                         int irrelevantInTopK,
                         int returnedCount,
                         List<RetrievedChunk> candidates) {
    }

    public boolean judged() {
        return judgeScore != null && judgeCorrect != null;
    }
}
