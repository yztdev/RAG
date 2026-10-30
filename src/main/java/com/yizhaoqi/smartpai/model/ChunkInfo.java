package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * ChunkInfo 类用于表示文件分块的信息
 * 它是一个实体类，与数据库中的 'chunk_info' 表对应
 * 该类用来存储每个文件分块的元数据，包括分块的唯一标识、属于哪个文件、分块的顺序、分块的校验码和存储位置
 */
@Data
@Entity
@Table(
        name = "chunk_info",
        uniqueConstraints = @UniqueConstraint(name = "uk_chunk_user_md5_index", columnNames = {"user_id", "file_md5", "chunk_index"})
)
public class ChunkInfo {
    /**
     * 分块信息的唯一标识符
     * 由数据库自动生成，用于唯一确定一个分块信息
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 文件的MD5值
     * 用于标识一个文件，同一个文件的MD5值相同，不同文件的MD5值不同
     */
    @Column(name = "file_md5", nullable = false, length = 32)
    private String fileMd5;

    /**
     * 上传分片的用户ID
     * 分片按用户隔离：两个用户同时上传同一个文件时，各自的分片记录和对象互不影响。
     * 带默认值是为了兼容回滚：旧版本插入分片时不写这一列
     */
    @Column(name = "user_id", nullable = false, length = 64, columnDefinition = "VARCHAR(64) DEFAULT ''")
    private String userId;

    /**
     * 分块的索引号
     * 表示文件中的第几个分块，用于保持分块的顺序
     */
    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    /**
     * 分块的MD5值
     * 每个分块的唯一标识，用于校验分块的完整性和正确性
     */
    @Column(name = "chunk_md5", nullable = false, length = 32)
    private String chunkMd5;

    /**
     * 分块的存储路径
     * 表示分块在系统中的存储位置，可以是绝对路径或相对路径
     */
    @Column(name = "storage_path", nullable = false, length = 255)
    private String storagePath;
}
