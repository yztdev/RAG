package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * embedding 模型升级时的全量重建索引配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.index.rebuild")
public class EmbeddingReindexProperties {

    /** 每批送入 embedding 接口的分块数，拿到向量立即写入新索引，不在内存里攒 */
    private int batchSize = 10;
    /** 触发全局 embedding 限流时的重试次数与退避时间 */
    private int rateLimitRetries = 5;
    private long rateLimitBackoffSeconds = 20;
    /** 切换前的抽样测试问题：每个问题在新索引上至少要召回一条，否则不切换 */
    private List<String> validationQueries = new ArrayList<>();
    /** 重建任务调用 embedding 使用的请求方 ID，以 system 开头可豁免用户配额 */
    private String requesterId = "system-reindex";
}
