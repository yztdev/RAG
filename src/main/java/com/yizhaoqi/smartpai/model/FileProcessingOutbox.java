package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 文件处理任务 outbox：业务状态和待投递消息在同一个数据库事务里写入，
 * 提交后再投递 Kafka，投递失败由定时任务按退避补发，保证状态改了消息一定会发出去
 */
@Data
@Entity
@Table(
        name = "file_processing_outbox",
        indexes = @Index(name = "idx_outbox_status_next_attempt", columnList = "status,next_attempt_at")
)
public class FileProcessingOutbox {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_md5", nullable = false, length = 32)
    private String fileMd5;

    @Column(name = "task_type", nullable = false, length = 32)
    private String taskType;

    /** FileProcessingTask 的 JSON */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    /** 下次可投递时间；被某个实例认领后推迟到租约结束，实现多实例互斥 */
    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;
}
