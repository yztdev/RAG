package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 上下文检索（Anthropic Contextual Retrieval，2024-09）：入库时让 LLM 给每个分块写一两句上下文说明，
 * 说明拼在分块前面一起做 embedding 和 BM25，原文单独保存用于展示、引用和证据片段。
 * 每个分块都要调用 LLM，成本不低，默认关闭。
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.contextual-retrieval")
public class ContextualRetrievalProperties {

    /** 总开关：关闭时入库和 embedding 重建都按分块原文建索引（已生成的说明保留在 MySQL，不删除） */
    private boolean enabled = false;
    /** 一次 LLM 调用处理几个相邻分块，同一份文档窗口只发送一次 */
    private int chunksPerRequest = 8;
    /** 窗口在这批分块前后各多带几个相邻分块，交代分块所在的章节 */
    private int neighbourChunks = 2;
    /** 送给 LLM 的文档窗口（标题 + 相邻分块 + 本批分块）最大字符数，文档比上下文窗口长得多时靠它截断 */
    private int maxWindowChars = 6000;
    /** 单个分块的上下文说明最大字符数，超出截断 */
    private int maxContextChars = 200;
    /** 同时在途的 LLM 调用数（全局共享，所有文件一起受这个上限约束） */
    private int concurrency = 2;
    /** 单次调用的最大输出 tokens */
    private int maxCompletionTokens = 1200;
    /** 触发全局 LLM token 预算限流时的重试次数和间隔，重试耗尽后这批分块按原文入索引 */
    private int rateLimitRetries = 2;
    private int rateLimitBackoffSeconds = 10;
    /** 调用方 ID，以 system 开头不占上传用户的配额，仍受全局 LLM token 预算约束 */
    private String requesterId = "system-contextual";
}
