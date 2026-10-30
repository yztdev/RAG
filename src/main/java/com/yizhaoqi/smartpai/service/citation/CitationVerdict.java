package com.yizhaoqi.smartpai.service.citation;

/**
 * 单个引用编号的校验结论
 *
 * @param reason 仅 SUSPICIOUS 时有值，说明哪句话、为什么不被支撑
 */
public record CitationVerdict(Status status, String reason) {

    public enum Status {
        /** 所有引用该编号的句子都能被片段支撑 */
        SUPPORTED,
        /** 至少一句引用该编号的话不被片段支撑，前端应降低展示优先级 */
        SUSPICIOUS
    }
}
