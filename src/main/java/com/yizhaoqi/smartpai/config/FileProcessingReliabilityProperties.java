package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件处理链路的可靠性参数：卡住任务恢复、废弃上传清理、Kafka 投递 outbox
 */
@Data
@Component
@ConfigurationProperties(prefix = "file.processing.reliability")
public class FileProcessingReliabilityProperties {

    /** 定时恢复 / 清理任务总开关 */
    private boolean recoveryEnabled = true;
    /** 恢复任务扫描间隔 */
    private long recoveryIntervalMs = 60_000L;
    /** 每轮每类最多处理多少条记录 */
    private int recoveryBatchSize = 50;
    /** 向量化心跳超过该时长没有刷新视为卡住，需大于单批分块 embedding + 写 ES 的最长耗时 */
    private long vectorizationHeartbeatTimeoutSeconds = 600L;
    /** 合并中超过该时长视为卡住，改回上传中让用户重新合并 */
    private long mergeTimeoutSeconds = 600L;
    /** 卡住的向量化最多自动重新投递几次，超过后标记失败并告警 */
    private int maxRetries = 3;
    /** 上传中超过该时长没有新分片视为放弃，清理分片对象、分片记录和 Redis bitmap */
    private long abandonedUploadHours = 24L;
    /** 分片上传 bitmap 的过期时间，每次写入都会续期；数据库仍是事实来源，过期后可以回填 */
    private long chunkBitmapTtlHours = 48L;

    private final Outbox outbox = new Outbox();

    @Data
    public static class Outbox {
        /** outbox 补发任务开关 */
        private boolean dispatcherEnabled = true;
        /** 补发扫描间隔 */
        private long dispatchIntervalMs = 5_000L;
        /** 每轮最多补发多少条 */
        private int batchSize = 50;
        /** 单条消息等待 Kafka 确认的超时时间 */
        private long sendTimeoutSeconds = 10L;
        /** 认领一条消息后的租约时长，发送中的实例崩溃后租约到期由其他实例接手 */
        private long claimLeaseSeconds = 60L;
        /** 发送失败后的最大退避间隔 */
        private long maxBackoffSeconds = 300L;
        /** 已发送记录保留天数 */
        private int sentRetentionDays = 7;
    }
}
