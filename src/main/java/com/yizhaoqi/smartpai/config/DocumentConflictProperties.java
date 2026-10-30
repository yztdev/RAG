package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文档冲突检测配置：新文档向量化完成后，逐块和同可见范围内其他文件的分块做 KNN 比对，
 * 向量相似度高、但数字 / 版本号 / 日期等关键值不同的分块对标记为疑似冲突，交给管理员人工确认。
 * 检测只写冲突记录，不会自动删除或覆盖任何文档。
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.conflict")
public class DocumentConflictProperties {

    private boolean enabled = true;
    /** 余弦相似度阈值（ES cosine 的 _score 会换算回余弦值再比较） */
    private double similarityThreshold = 0.9;
    /** 把数字、版本号、日期替换成占位符后，两段文字的字符二元组 Dice 相似度阈值 */
    private double maskedTextSimilarityThreshold = 0.8;
    /** 每个新文件最多检查多少个分块（按 chunkId 顺序取前 N 个） */
    private int maxChunksPerFile = 200;
    /** 每个分块取多少个最相似的其他文件分块 */
    private int neighborsPerChunk = 3;
    /** 单个文件一次最多记录多少条疑似冲突，防止模板类文档刷屏 */
    private int maxConflictsPerFile = 50;
    /** 冲突记录里保存的分块摘要长度 */
    private int snippetChars = 300;

    private LlmConfirm llmConfirm = new LlmConfirm();

    @Data
    public static class LlmConfirm {
        /** 开启后，规则筛出的候选再合并成一次 LLM 调用做二次确认；调用失败时保留规则结果 */
        private boolean enabled = false;
        /** 单个分块送入模型的最大字符数 */
        private int maxChunkChars = 800;
        private int maxCompletionTokens = 800;
        /** 以 system 开头可豁免用户配额 */
        private String requesterId = "system-conflict";
    }
}
