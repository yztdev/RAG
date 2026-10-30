package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 离线 RAG 评测配置：检索看 Recall@K / MRR，生成看 LLM-as-a-judge 打分，按比例抽样给人工复核。
 */
@Data
@Component
@ConfigurationProperties(prefix = "eval")
public class RagEvalProperties {

    /** 评测报告（JSON）输出目录 */
    private String outputDir = "eval-reports";
    /** 通过 datasetPath 指定数据集时只允许读取该目录下的文件；也可以用 classpath:eval/xxx.jsonl */
    private String datasetDir = "eval-datasets";
    /** 随机抽样给人工复核的比例（评委输出解析失败的用例总是需要复核） */
    private double humanReviewSampleRate = 0.1;
    /** 抽样随机种子，为空时每次随机；固定种子便于两次评测抽到同一批用例 */
    private Long randomSeed;
    private int defaultTopK = 5;
    private int maxTopK = 20;
    /** 单次评测最多用例数 */
    private int maxCases = 500;
    /** 生成回答的最大输出 tokens；推理模型的思考过程也计入这个预算，给小了会只思考不输出 */
    private int generationMaxTokens = 4000;
    /** 评委模型的最大输出 tokens，同样要给思考过程留出余量 */
    private int judgeMaxTokens = 2000;
    /** 输出为空且因长度截断时，重试把预算翻倍，但不超过该值 */
    private int maxRetryTokens = 8000;
    private int generationTimeoutSeconds = 90;
    private int judgeTimeoutSeconds = 60;
    /** 送给评委的每条检索证据最大字符数 */
    private int maxEvidenceChars = 800;
    /** 生成和评委调用使用的请求方 ID，以 system 开头可豁免用户配额；检索仍以发起评测的管理员身份执行 */
    private String requesterId = "system-eval";

    /** 评委与人工一致率的告警阈值 */
    private HumanAgreement humanAgreement = new HumanAgreement();

    @Data
    public static class HumanAgreement {
        /** 评委与人工在 correct 上的一致率低于该值时，评测汇总标记为需要重新校准 */
        private double minAgreementRate = 0.8;
        /** Cohen's kappa 下限，为空时不检查 */
        private Double minKappa;
        /** 评委和人工都给出结论的用例少于该数量时只提示样本不足 */
        private int minComparableCases = 5;
    }
}
