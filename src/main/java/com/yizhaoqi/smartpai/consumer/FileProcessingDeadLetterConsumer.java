package com.yizhaoqi.smartpai.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.FileProcessingDeadLetter;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.repository.FileProcessingDeadLetterRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 死信主题消费者：重试耗尽的文件处理任务落库并告警，供排查和人工重放。
 * 按死信记录在主题中的位置去重，重复消费不会重复落库
 */
@Component
public class FileProcessingDeadLetterConsumer {

    private static final Logger logger = LoggerFactory.getLogger(FileProcessingDeadLetterConsumer.class);

    private final FileProcessingDeadLetterRepository deadLetterRepository;
    private final ObjectMapper objectMapper;

    public FileProcessingDeadLetterConsumer(FileProcessingDeadLetterRepository deadLetterRepository,
                                            ObjectMapper objectMapper) {
        this.deadLetterRepository = deadLetterRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(id = "file-processing-dlt-listener",
            topics = "#{kafkaConfig.getFileProcessingDltTopic()}",
            groupId = "#{kafkaConfig.getDeadLetterGroupId()}",
            containerFactory = "deadLetterListenerContainerFactory")
    public void onDeadLetter(ConsumerRecord<String, Object> record) {
        if (deadLetterRepository.existsBySourceTopicAndSourcePartitionAndSourceOffset(
                record.topic(), record.partition(), record.offset())) {
            return;
        }

        FileProcessingDeadLetter deadLetter = new FileProcessingDeadLetter();
        deadLetter.setSourceTopic(record.topic());
        deadLetter.setSourcePartition(record.partition());
        deadLetter.setSourceOffset(record.offset());
        deadLetter.setErrorMessage(truncate(headerValue(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE)));
        Object value = record.value();
        if (value instanceof FileProcessingTask task) {
            deadLetter.setFileMd5(task.getFileMd5());
            deadLetter.setTaskType(task.getTaskType());
        }
        deadLetter.setPayload(serialize(value));

        try {
            deadLetterRepository.save(deadLetter);
        } catch (DataIntegrityViolationException duplicate) {
            return;
        }
        logger.error("[ALERT] 文件处理任务重试耗尽进入死信队列: fileMd5={}, taskType={}, dltOffset={}-{}, error={}",
                deadLetter.getFileMd5(), deadLetter.getTaskType(), record.partition(), record.offset(), deadLetter.getErrorMessage());
    }

    private String headerValue(ConsumerRecord<String, Object> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private String serialize(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
