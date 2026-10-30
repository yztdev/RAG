package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 全局 AI 相关配置，包含 Prompt 模板和生成参数。
 */
@Component
@ConfigurationProperties(prefix = "ai")
@Data
public class AiProperties {

    private Prompt prompt = new Prompt();
    private Generation generation = new Generation();
    private CitationVerification citationVerification = new CitationVerification();

    /**
     * 引用事后校验：回答生成后把"句子 + 被引用片段"交给模型判断是否支撑，不支撑的引用标记为可疑
     */
    @Data
    public static class CitationVerification {
        /** 默认关闭，开启后每次带引用的回答会多一次 LLM 调用（计入当前用户的 LLM 配额） */
        private boolean enabled = false;
        /** 单次最多校验的（句子, 引用）对数，超出部分不校验 */
        private int maxClaims = 12;
        /** 句子最大字符数 */
        private int maxClaimChars = 300;
        /** 引用片段最大字符数 */
        private int maxReferenceChars = 800;
        /** 校验调用的最大输出 tokens */
        private int maxCompletionTokens = 800;
        /** 校验超时，超时后按未校验处理，不影响回答 */
        private int timeoutSeconds = 20;
    }

    @Data
    public static class Prompt {
        /** 规则文案 */
        private String rules;
        /** 引用开始分隔符 */
        private String refStart;
        /** 引用结束分隔符 */
        private String refEnd;
        /** 无检索结果时的占位文案 */
        private String noResultText;
    }

    @Data
    public static class Generation {
        /** 采样温度 */
        private Double temperature = 0.3;
        /** 最大输出 tokens */
        private Integer maxTokens = 2000;
        /** nucleus top-p */
        private Double topP = 0.9;
    }
} 