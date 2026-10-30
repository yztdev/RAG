package com.yizhaoqi.smartpai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 疑似文档冲突：新文件的某个分块和已有文件的某个分块语义高度相似，但关键值（数字 / 版本号 / 日期）不同。
 * 只记录、不处理，由管理员决定保留哪一篇。
 */
@Data
@Entity
@Table(name = "document_conflicts", indexes = {
        @Index(name = "idx_doc_conflict_pair_key", columnList = "pair_key"),
        @Index(name = "idx_doc_conflict_status", columnList = "status"),
        @Index(name = "idx_doc_conflict_new_md5", columnList = "new_file_md5"),
        @Index(name = "idx_doc_conflict_existing_md5", columnList = "existing_file_md5")
})
public class DocumentConflict {

    public enum Status {
        /** 待管理员处理 */
        PENDING,
        /** 保留新文档，已删除旧文档 */
        KEEP_NEW,
        /** 保留旧文档，已删除新文档 */
        KEEP_EXISTING,
        /** 管理员确认不是冲突 */
        IGNORED,
        /** 冲突一方已在处理其他冲突时被删除，本条自动失效 */
        OBSOLETE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 与方向无关的分块对标识，用于去重：小的一侧在前，形如 md5a#1|md5b#7 */
    @Column(name = "pair_key", nullable = false, length = 160)
    private String pairKey;

    @Column(name = "new_file_md5", nullable = false, length = 32)
    private String newFileMd5;

    @Column(name = "new_chunk_id", nullable = false)
    private Integer newChunkId;

    @Column(name = "new_file_name")
    private String newFileName;

    @Column(name = "existing_file_md5", nullable = false, length = 32)
    private String existingFileMd5;

    @Column(name = "existing_chunk_id", nullable = false)
    private Integer existingChunkId;

    @Column(name = "existing_file_name")
    private String existingFileName;

    /** 两个分块向量的余弦相似度 */
    @Column(nullable = false)
    private Double similarity;

    /** 屏蔽关键值后的文本相似度 */
    @Column(name = "masked_similarity")
    private Double maskedSimilarity;

    @Column(name = "new_snippet", length = 1000)
    private String newSnippet;

    @Column(name = "existing_snippet", length = 1000)
    private String existingSnippet;

    /** 只在新分块中出现的关键值 */
    @Column(name = "new_values", length = 500)
    private String newValues;

    /** 只在旧分块中出现的关键值 */
    @Column(name = "existing_values", length = 500)
    private String existingValues;

    /** 开启 LLM 二次确认时模型给出的理由 */
    @Column(name = "llm_reason", length = 500)
    private String llmReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_by", length = 64)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;
}
