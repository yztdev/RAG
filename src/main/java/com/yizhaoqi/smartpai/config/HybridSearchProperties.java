package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 混合检索配置：BM25 与 KNN 双路独立召回 → RRF 融合 → rerank 精排 → 分数阈值截断。
 * topK 只作为最终返回条数的上限，噪声靠 rerank 阈值挡住。
 */
@Data
@Component
@ConfigurationProperties(prefix = "search.hybrid")
public class HybridSearchProperties {

    /** 每一路召回窗口 = topK * recallMultiplier */
    private int recallMultiplier = 30;
    /** 每一路召回窗口上限，防止 topK 过大时单路拉回过多文档 */
    private int maxRecallWindow = 300;
    /** KNN num_candidates = 召回窗口 * 该倍数（ES 上限 10000） */
    private int knnNumCandidatesMultiplier = 2;
    /** RRF 平滑常数 k，score = Σ 1 / (k + rank) */
    private int rrfK = 60;
    /** 双路召回的等待超时 */
    private int recallTimeoutSeconds = 10;

    private Rerank rerank = new Rerank();
    private Keyword keyword = new Keyword();

    /**
     * 跳过或 rerank 失败、按 RRF 顺序兜底时的分数阈值（原始 RRF 分，score = Σ 1/(rrfK + 名次)，按两路都成功时的量纲配置）。
     * 两路都排第一时约 0.0328，只被一路召回时最多 1/61 ≈ 0.0164；只有一路召回成功时阈值自动减半，含义保持一致。
     * 默认 0.028 ≈ 两路平均名次在 11 名以内。评测集 paismart-eval-v1 上 RRF 前 5 名几乎都是两路共同召回的（0.026 到 0.033），
     * 相关分块最低 0.029：阈值 0.028 时分块级召回率不变（80.4%），无答案题平均返回从 5 条降到 4 条；超过 0.030 召回率开始明显下降
     */
    private double rrfScoreThreshold = 0.028;

    /**
     * 纯文本降级（TEXT_ONLY）时 BM25 原始分没有上界，出口按 score / (score + 该值) 换算到 0 到 1。
     * 固定常数而不是 min-max：换算结果不随当批结果变化，跨查询可比
     */
    private double bm25ScoreSaturation = 10d;

    @Data
    public static class Keyword {
        /**
         * 文件名字段的权重（作为加分项，只给正文已经匹配的分块加分），0 表示不用文件名打分。
         * 评测集上权重越大 BM25 一路的排序越差（文件名不具描述性时，同一文件所有分块都被抬高），0.5 基本无损
         */
        private float fileNameBoost = 0.5f;
        /** 正文关键词匹配的 minimum_should_match，例如 "30%"；为空表示不限制（任意一个词命中即可） */
        private String minimumShouldMatch = "";
        /** 正文短语匹配的加分权重，0 表示不加短语分 */
        private float phraseBoost = 0f;
        /** 短语匹配允许的词间距 */
        private int phraseSlop = 2;
    }

    @Data
    public static class Rerank {
        /** 关闭后按 RRF 顺序直接截取 topK（不做阈值截断） */
        private boolean enabled = true;
        /** 百炼 / DashScope 文本排序接口 */
        private String url = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
        private String model = "gte-rerank-v2";
        /** 为空时复用激活的阿里云 embedding provider 的 API Key */
        private String apiKey = "";
        /** 只对 RRF 融合后的前 N 条候选做精排 */
        private int maxCandidates = 50;
        /** rerank 相关性分数低于该阈值的候选直接丢弃 */
        private double scoreThreshold = 0.1;
        /** 单个候选送入 rerank 的最大字符数 */
        private int maxDocumentChars = 2000;
        private int timeoutSeconds = 10;
        /** 问答请求剩余时间低于该值时跳过 rerank，直接按 RRF 顺序返回 */
        private long skipBelowMillis = 2000L;
    }
}
