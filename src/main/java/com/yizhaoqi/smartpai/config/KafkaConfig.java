package com.yizhaoqi.smartpai.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Configuration
public class KafkaConfig {

    private static final Logger logger = LoggerFactory.getLogger(KafkaConfig.class);

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.topic.file-processing}")
    private String fileProcessingTopic;

    @Value("${spring.kafka.topic.dlt}")
    private String fileProcessingDltTopic;

    @Value("${spring.kafka.topic.partitions:1}")
    private int topicPartitions;

    @Value("${spring.kafka.topic.replication-factor:1}")
    private short topicReplicationFactor;

    @Value("${spring.kafka.consumer.group-id}")
    private String fileProcessingGroupId;

    @Value("${spring.kafka.consumer.auto-offset-reset}")
    private String autoOffsetReset;

    @Value("${spring.kafka.consumer.properties.spring.json.trusted.packages}")
    private String trustedPackages;

    /** 单个 PDF 的 LiteParse 解析上限 */
    @Value("${file.parsing.liteparse.timeout-seconds:300}")
    private long liteparseTimeoutSeconds;

    /** 拿解析并发许可的最长等待 */
    @Value("${file.parsing.permit-timeout-seconds:120}")
    private long parsePermitTimeoutSeconds;

    private final FileProcessingReliabilityProperties reliabilityProperties;

    public KafkaConfig(FileProcessingReliabilityProperties reliabilityProperties) {
        this.reliabilityProperties = reliabilityProperties;
    }


    public String getFileProcessingTopic() {
        return fileProcessingTopic;
    }

    public String getFileProcessingGroupId() {
        return fileProcessingGroupId;
    }

    public String getFileProcessingDltTopic() {
        return fileProcessingDltTopic;
    }

    public String getDeadLetterGroupId() {
        return fileProcessingGroupId + "-dlt";
    }

    @Bean
    public NewTopic fileProcessingNewTopic() {
        return TopicBuilder.name(fileProcessingTopic)
                .partitions(topicPartitions)
                .replicas(topicReplicationFactor)
                .build();
    }

    @Bean
    public NewTopic fileProcessingDltNewTopic() {
        return TopicBuilder.name(fileProcessingDltTopic)
                .partitions(topicPartitions)
                .replicas(topicReplicationFactor)
                .build();
    }

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> config = new HashMap<>();
//        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // 可靠投递配置
        config.put(ProducerConfig.ACKS_CONFIG, "all"); // 全部 ISR 落盘才确认
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // 幂等生产者
        config.put(ProducerConfig.RETRIES_CONFIG, 3); // 自动重试 3 次
        // Kafka 不可用时最多阻塞 10 秒，失败的消息留在 outbox 里由定时任务补发，不让请求线程长时间挂住
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);

        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(config);
        // 设置事务前缀，启用事务能力
        factory.setTransactionIdPrefix("file-upload-tx-");
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> config = new HashMap<>();
//        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // 禁用自动提交偏移量
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, fileProcessingGroupId);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, trustedPackages);
        // 生产端用事务发送，消费端只读已提交的消息，否则事务中止的消息也会被消费
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        // 一条消息就是一份文件的解析 + 向量化，耗时以分钟计：一次只拉一条，避免一批里后面的消息跟着等
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        // 两次 poll 的最大间隔要大于单条消息的最长处理时间，否则消费者被踢出消费组、触发再均衡和重复投递。
        // 和向量化心跳超时对齐：处理超过这个时间没进展，恢复任务也会接管
        long maxPollIntervalMs = maxPollIntervalMs();
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, (int) maxPollIntervalMs);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    long maxPollIntervalMs() {
        long intervalMs = TimeUnit.SECONDS.toMillis(Math.max(reliabilityProperties.getVectorizationHeartbeatTimeoutSeconds(), 60L));
        long worstParseMs = TimeUnit.SECONDS.toMillis(liteparseTimeoutSeconds + parsePermitTimeoutSeconds);
        if (worstParseMs >= intervalMs) {
            logger.warn("max.poll.interval.ms={}ms 不大于最坏解析耗时（LiteParse {}s + 等待解析许可 {}s），超长文件可能触发再均衡",
                    intervalMs, liteparseTimeoutSeconds, parsePermitTimeoutSeconds);
        }
        return intervalMs;
    }

    // 带自动重试和死信队列的监听器工厂
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            KafkaTemplate<String, Object> kafkaTemplate) {
        // 当重试失败后，消息发送至 file-processing-dlt 主题，分区与原消息保持一致
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(fileProcessingDltTopic, record.partition()));

        // 固定退避策略：每 3 秒重试一次，最多重试 4 次（加首次共 5 次）
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(3000L, 4));

        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    /**
     * 死信主题专用监听器工厂：落库失败重试两次后只记日志，不再转发到死信主题，避免死信消费失败时自我循环
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> deadLetterListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, 2)));
        return factory;
    }
}
