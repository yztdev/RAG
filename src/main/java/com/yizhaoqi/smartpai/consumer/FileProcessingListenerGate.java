package com.yizhaoqi.smartpai.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 文件处理 Kafka 监听器的暂停 / 恢复闸门。
 * 多个来源（堆内存背压、embedding 索引切换）可以各自申请暂停，只有所有原因都解除后才恢复消费。
 * 暂停只是不再 poll 新消息，压力留在 Kafka 里，不往 JVM 里堆；正在处理的任务会正常跑完。
 */
@Component
public class FileProcessingListenerGate {

    public static final String LISTENER_ID = "fileProcessingListener";
    public static final String REASON_HEAP_PRESSURE = "heap-pressure";
    public static final String REASON_INDEX_SWITCH = "index-switch";

    private static final Logger logger = LoggerFactory.getLogger(FileProcessingListenerGate.class);

    private final ObjectProvider<KafkaListenerEndpointRegistry> registryProvider;
    private final Set<String> pauseReasons = new LinkedHashSet<>();

    public FileProcessingListenerGate(ObjectProvider<KafkaListenerEndpointRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    public synchronized void pause(String reason) {
        boolean wasRunning = pauseReasons.isEmpty();
        if (!pauseReasons.add(reason)) {
            return;
        }
        if (wasRunning) {
            MessageListenerContainer container = container();
            if (container != null) {
                container.pause();
            }
            logger.warn("暂停文件处理 Kafka 消费: reason={}, containerFound={}", reason, container != null);
        } else {
            logger.info("文件处理 Kafka 消费已处于暂停状态，追加暂停原因: {}, 当前原因: {}", reason, pauseReasons);
        }
    }

    public synchronized void resume(String reason) {
        if (!pauseReasons.remove(reason)) {
            return;
        }
        if (!pauseReasons.isEmpty()) {
            logger.info("解除暂停原因 {}，但仍有其他原因保持暂停: {}", reason, pauseReasons);
            return;
        }
        MessageListenerContainer container = container();
        if (container != null) {
            container.resume();
        }
        logger.info("恢复文件处理 Kafka 消费: reason={}", reason);
    }

    public synchronized boolean isPaused() {
        return !pauseReasons.isEmpty();
    }

    public synchronized Set<String> pauseReasons() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(pauseReasons));
    }

    private MessageListenerContainer container() {
        KafkaListenerEndpointRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return null;
        }
        return registry.getListenerContainer(LISTENER_ID);
    }
}
