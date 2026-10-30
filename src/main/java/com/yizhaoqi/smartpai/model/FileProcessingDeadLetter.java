package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 重试耗尽进入死信主题的文件处理任务，落库后供排查和人工重放
 */
@Data
@Entity
@Table(
        name = "file_processing_dead_letter",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_dead_letter_source",
                columnNames = {"source_topic", "source_partition", "source_offset"}
        ),
        indexes = @Index(name = "idx_dead_letter_md5", columnList = "file_md5")
)
public class FileProcessingDeadLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_md5", length = 32)
    private String fileMd5;

    @Column(name = "task_type", length = 32)
    private String taskType;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** 死信记录自身在 DLT 中的位置，用于幂等落库 */
    @Column(name = "source_topic", nullable = false, length = 255)
    private String sourceTopic;

    @Column(name = "source_partition", nullable = false)
    private int sourcePartition;

    @Column(name = "source_offset", nullable = false)
    private long sourceOffset;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
