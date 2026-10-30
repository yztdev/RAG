package com.yizhaoqi.smartpai.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KafkaConsumerSettingsTest {

    @Test
    void pollsOneRecordAndAlignsPollIntervalWithHeartbeatTimeout() {
        FileProcessingReliabilityProperties reliability = new FileProcessingReliabilityProperties();
        KafkaConfig config = new KafkaConfig(reliability);
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "fileProcessingGroupId", "group");
        ReflectionTestUtils.setField(config, "trustedPackages", "*");
        ReflectionTestUtils.setField(config, "liteparseTimeoutSeconds", 300L);
        ReflectionTestUtils.setField(config, "parsePermitTimeoutSeconds", 120L);

        Map<String, Object> props = config.consumerFactory().getConfigurationProperties();

        assertEquals(1, props.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG));
        // 默认心跳超时 10 分钟，大于 LiteParse 300 秒 + 等待解析许可 120 秒
        assertEquals(600_000, props.get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG));
        assertEquals("read_committed", props.get(ConsumerConfig.ISOLATION_LEVEL_CONFIG));

        reliability.setVectorizationHeartbeatTimeoutSeconds(900);
        assertEquals(900_000, config.consumerFactory().getConfigurationProperties().get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG));
    }
}
