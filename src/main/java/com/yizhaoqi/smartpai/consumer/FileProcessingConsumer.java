package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.exception.InsufficientBalanceException;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.service.DocumentService;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.UploadService;
import com.yizhaoqi.smartpai.service.VectorizationService;
import com.yizhaoqi.smartpai.service.conflict.DocumentVectorizedEvent;
import com.yizhaoqi.smartpai.service.reliability.VectorizationLease;
import com.yizhaoqi.smartpai.service.reliability.VectorizationLeaseLostException;
import com.yizhaoqi.smartpai.service.reliability.VectorizationLeaseService;
import io.minio.errors.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

@Service
@Slf4j
public class FileProcessingConsumer {

    private final ParseService parseService;
    private final VectorizationService vectorizationService;
    private final DocumentService documentService;
    private final VectorizationLeaseService leaseService;
    private final UploadService uploadService;
    @Autowired
    private KafkaConfig kafkaConfig;
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;


    public FileProcessingConsumer(
            ParseService parseService,
            VectorizationService vectorizationService,
            DocumentService documentService,
            VectorizationLeaseService leaseService,
            UploadService uploadService
    ) {
        this.parseService = parseService;
        this.vectorizationService = vectorizationService;
        this.documentService = documentService;
        this.leaseService = leaseService;
        this.uploadService = uploadService;
    }

    // id 供 FileProcessingListenerGate 在堆内存背压 / 索引切换时暂停、恢复消费
    @KafkaListener(id = FileProcessingListenerGate.LISTENER_ID,
            topics = "#{kafkaConfig.getFileProcessingTopic()}",
            groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
    public void processTask(FileProcessingTask task) {
        log.info("Received task: {}", task);
        log.info("文件权限信息: userId={}, orgTag={}, isPublic={}", 
                task.getUserId(), task.getOrgTag(), task.isPublic());

        // 认领：上传任务已完成直接跳过（Kafka 重复投递），已有消费者在处理且心跳未超时也跳过
        Optional<VectorizationLease> claimed = leaseService.claim(task);
        if (claimed.isEmpty()) {
            return;
        }
        VectorizationLease lease = claimed.get();

        try (InputStream fileStream = openSourceStream(task)) {
            // 先清理上一次处理留下的分块和索引，重复投递、中途失败重试都从干净状态重新解析，不会插入重复分块
            documentService.clearIndexedData(task.getFileMd5());

            parseService.parseAndSave(task.getFileMd5(), fileStream,
                    task.getUserId(), task.getOrgTag(), task.isPublic());
            log.info("文件解析完成，fileMd5: {}", task.getFileMd5());
            leaseService.heartbeat(lease);

            String requesterId = task.getRequesterId() == null || task.getRequesterId().isBlank()
                    ? task.getUserId()
                    : task.getRequesterId();
            VectorizationService.VectorizationUsageResult vectorizationResult = vectorizationService.vectorizeWithUsage(
                    task.getFileMd5(),
                    task.getUserId(),
                    task.getOrgTag(),
                    task.isPublic(),
                    requesterId,
                    () -> leaseService.heartbeat(lease)
            );
            if (leaseService.complete(lease, vectorizationResult)) {
                log.info("向量化完成，fileMd5: {}", task.getFileMd5());
                publishVectorized(task.getFileMd5());
            }
        } catch (VectorizationLeaseLostException e) {
            // 任务已被定时任务改回待处理并重新投递，交给新的认领者，这条消息正常提交
            log.warn("向量化认领已失效，放弃本次处理: fileMd5={}", task.getFileMd5());
        } catch (Exception e) {
            if (isInsufficientBalance(e)) {
                // 余额不足重试多少次结果都一样，每次重试还要重新解析整份文件、占住消费线程，直接标记失败并提交消息
                leaseService.fail(lease, e);
                log.warn("用户余额不足，向量化失败且不重试: fileMd5={}, userId={}, reason={}",
                        task.getFileMd5(), task.getUserId(), e.getMessage());
                return;
            }
            leaseService.fail(lease, e);
            log.error("Error processing task: {}", task, e);
            // 抛出异常让 Kafka 的 DefaultErrorHandler 捕获并触发重试 / 死信
            throw new RuntimeException("Error processing task", e);
        }
    }

    private static boolean isInsufficientBalance(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof InsufficientBalanceException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 通知冲突检测等后置任务。后置任务异步执行，这里任何异常都不能影响入库结果和 Kafka 提交。
     */
    private void publishVectorized(String fileMd5) {
        if (eventPublisher == null) {
            return;
        }
        try {
            eventPublisher.publishEvent(new DocumentVectorizedEvent(fileMd5));
        } catch (Exception e) {
            log.warn("发布向量化完成事件失败，忽略: fileMd5={}, error={}", fileMd5, e.getMessage());
        }
    }

    /**
     * 优先直接读 MinIO 里的合并文件：预签名 URL 一小时就过期，outbox 补发、卡住重投、重建任务都可能晚于这个时间。
     * 合并对象读不到时再退回消息里的路径（兼容旧消息和本地文件路径）
     */
    private InputStream openSourceStream(FileProcessingTask task) throws Exception {
        try {
            return uploadService.getMergedFileStream(task.getFileMd5());
        } catch (Exception e) {
            if (task.getFilePath() == null || task.getFilePath().isBlank()) {
                throw e;
            }
            log.warn("读取 MinIO 合并文件失败，改用消息中的文件路径: fileMd5={}, error={}", task.getFileMd5(), e.getMessage());
        }
        InputStream fileStream = downloadFileFromStorage(task.getFilePath());
        if (fileStream == null) {
            throw new IOException("流为空");
        }
        return fileStream.markSupported() ? fileStream : new BufferedInputStream(fileStream);
    }

    /**
     * 模拟从存储系统下载文件
     *
     * @param filePath 文件路径或 URL
     * @return 文件输入流
     */
    private InputStream downloadFileFromStorage(String filePath) throws ServerException, InsufficientDataException, ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        log.info("Downloading file from storage: {}", filePath);

        try {
            // 如果是文件系统路径
            File file = new File(filePath);
            if (file.exists()) {
                log.info("Detected file system path: {}", filePath);
                return new FileInputStream(file);
            }

            // 如果是远程 URL
            if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
                log.info("Detected remote URL: {}", filePath);
                URL url = new URL(filePath);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(30000); // 连接超时30秒
                connection.setReadTimeout(180000);   // 读取超时时间3分钟

                // 添加必要的请求头
                connection.setRequestProperty("User-Agent", "SmartPAI-FileProcessor/1.0");

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    log.info("Successfully connected to URL, starting download...");
                    return connection.getInputStream();
                } else if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    log.error("Access forbidden - possible expired presigned URL");
                    throw new IOException("Access forbidden - the presigned URL may have expired");
                } else {
                    log.error("Failed to download file, HTTP response code: {} for URL: {}", responseCode, filePath);
                    throw new IOException(String.format("Failed to download file, HTTP response code: %d", responseCode));
                }
            }

            // 如果既不是文件路径也不是 URL
            throw new IllegalArgumentException("Unsupported file path format: " + filePath);
        } catch (Exception e) {
            log.error("Error downloading file from storage: {}", filePath, e);
            return null; // 或者抛出异常
        }
    }

}
