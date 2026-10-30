package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.FileUpload;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface FileUploadRepository extends JpaRepository<FileUpload, Long> {
    Optional<FileUpload> findFirstByFileMd5OrderByCreatedAtDesc(String fileMd5);

    List<FileUpload> findAllByFileMd5(String fileMd5);

    List<FileUpload> findAllByVectorizationStatusIsNull();

    List<FileUpload> findAllByFileMd5AndUserIdOrderByCreatedAtDesc(String fileMd5, String userId);

    Optional<FileUpload> findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(String fileMd5, String userId);

    Optional<FileUpload> findFirstByFileMd5AndIsPublicTrueOrderByCreatedAtDesc(String fileMd5);

    Optional<FileUpload> findFirstByFileNameAndIsPublicTrueOrderByCreatedAtDesc(String fileName);

    Optional<FileUpload> findFirstByOrderByMergedAtDesc();
    
    long countByFileMd5(String fileMd5);

    long countByFileMd5AndUserId(String fileMd5, String userId);
    
    @Transactional
    @Modifying
    @Query("delete from FileUpload f where f.fileMd5 = :fileMd5")
    int deleteByFileMd5(@Param("fileMd5") String fileMd5);

    @Transactional
    @Modifying
    @Query("delete from FileUpload f where f.fileMd5 = :fileMd5 and f.userId = :userId")
    int deleteByFileMd5AndUserId(@Param("fileMd5") String fileMd5, @Param("userId") String userId);
    
    /**
     * 查询用户自己的文件和公开文件
     */
    List<FileUpload> findByUserIdOrIsPublicTrue(String userId);
    
    /**
     * 查询用户可访问的所有文件（考虑层级标签权限）
     * 包括：1. 用户自己上传的文件
     *      2. 公开的文件
     *      3. 用户所属组织的文件（包含层级关系）
     *
     * @param userId 用户ID
     * @param orgTagList 用户有效的组织标签列表（包含层级结构）
     * @return 用户可访问的文件列表
     */
    @Query("SELECT f FROM FileUpload f WHERE f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false)")
    List<FileUpload> findAccessibleFilesWithTags(@Param("userId") String userId, @Param("orgTagList") List<String> orgTagList);
    
    /**
     * 查询用户可访问的所有文件（原始方法，保留向后兼容性）
     * 
     * @param userId 用户ID
     * @param orgTagList 用户所属的组织标签列表（逗号分隔）
     * @return 用户可访问的文件列表
     */
    @Query("SELECT f FROM FileUpload f WHERE f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false)")
    List<FileUpload> findAccessibleFiles(@Param("userId") String userId, @Param("orgTagList") List<String> orgTagList);
    
    /**
     * 查询用户自己上传的所有文件
     * 
     * @param userId 用户ID
     * @return 用户上传的文件列表
     */
    List<FileUpload> findByUserId(String userId);

    List<FileUpload> findByUserIdAndFileNameOrderByCreatedAtDesc(String userId, String fileName);

    List<FileUpload> findByFileMd5In(List<String> md5List);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FileUpload f SET f.status = :newStatus WHERE f.id = :id AND f.status = :currentStatus")
    int updateStatusIfCurrent(@Param("id") Long id,
                              @Param("currentStatus") int currentStatus,
                              @Param("newStatus") int newStatus);

    /**
     * 状态条件更新并刷新心跳：进入合并中时记下开始时间，定时任务据此发现卡住的合并
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FileUpload f SET f.status = :newStatus, f.heartbeatAt = :now WHERE f.id = :id AND f.status = :currentStatus")
    int updateStatusIfCurrent(@Param("id") Long id,
                              @Param("currentStatus") int currentStatus,
                              @Param("newStatus") int newStatus,
                              @Param("now") LocalDateTime now);

    /**
     * 合并完成：只有仍处于合并中的记录才能改成已完成，影响行数为 0 说明状态已被别的请求或定时任务改掉
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FileUpload f SET f.status = :completedStatus, f.mergedAt = :now, f.heartbeatAt = :now " +
            "WHERE f.id = :id AND f.status = :mergingStatus")
    int completeMergeIfMerging(@Param("id") Long id,
                               @Param("mergingStatus") int mergingStatus,
                               @Param("completedStatus") int completedStatus,
                               @Param("now") LocalDateTime now);

    /**
     * 分片到达时刷新上传心跳，长时间没有新分片的上传会被定时任务当作废弃上传清理
     */
    @Transactional
    @Modifying
    @Query("UPDATE FileUpload f SET f.heartbeatAt = :now WHERE f.id = :id AND f.status = :uploadingStatus")
    int touchUploadHeartbeat(@Param("id") Long id,
                             @Param("uploadingStatus") int uploadingStatus,
                             @Param("now") LocalDateTime now);

    /**
     * 消费者认领向量化任务。已有消费者在处理且心跳未超时时认领失败，避免重复投递的消息并发处理同一个文件
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FileUpload f SET f.vectorizationStatus = 'PROCESSING', f.vectorizationErrorMessage = null, " +
            "f.processingStartedAt = :now, f.heartbeatAt = :now " +
            "WHERE f.id = :id AND (f.vectorizationStatus IS NULL OR f.vectorizationStatus <> 'PROCESSING' " +
            "OR f.heartbeatAt IS NULL OR f.heartbeatAt < :staleBefore)")
    int claimVectorization(@Param("id") Long id,
                           @Param("now") LocalDateTime now,
                           @Param("staleBefore") LocalDateTime staleBefore);

    /**
     * 向量化心跳，带认领时间做条件：任务被定时任务改回待处理后影响行数为 0，旧消费者据此放弃
     */
    @Transactional
    @Modifying
    @Query("UPDATE FileUpload f SET f.heartbeatAt = :now " +
            "WHERE f.id = :id AND f.vectorizationStatus = 'PROCESSING' AND f.processingStartedAt = :claimedAt")
    int heartbeatVectorization(@Param("id") Long id,
                               @Param("claimedAt") LocalDateTime claimedAt,
                               @Param("now") LocalDateTime now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FileUpload f SET f.vectorizationStatus = 'COMPLETED', f.vectorizationErrorMessage = null, " +
            "f.actualEmbeddingTokens = :tokens, f.actualChunkCount = :chunkCount, f.heartbeatAt = :now " +
            "WHERE f.id = :id AND f.vectorizationStatus = 'PROCESSING' AND f.processingStartedAt = :claimedAt")
    int completeVectorizationIfOwner(@Param("id") Long id,
                                     @Param("claimedAt") LocalDateTime claimedAt,
                                     @Param("tokens") Long tokens,
                                     @Param("chunkCount") Integer chunkCount,
                                     @Param("now") LocalDateTime now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FileUpload f SET f.vectorizationStatus = 'FAILED', f.vectorizationErrorMessage = :errorMessage, f.heartbeatAt = :now " +
            "WHERE f.id = :id AND f.vectorizationStatus = 'PROCESSING' AND f.processingStartedAt = :claimedAt")
    int failVectorizationIfOwner(@Param("id") Long id,
                                 @Param("claimedAt") LocalDateTime claimedAt,
                                 @Param("errorMessage") String errorMessage,
                                 @Param("now") LocalDateTime now);

    /**
     * 心跳超时的向量化任务。旧数据没有心跳时退回用最后更新时间判断
     */
    @Query("SELECT f FROM FileUpload f WHERE f.vectorizationStatus = 'PROCESSING' " +
            "AND (f.heartbeatAt < :staleBefore OR (f.heartbeatAt IS NULL AND f.mergedAt < :staleBefore)) ORDER BY f.id")
    List<FileUpload> findStaleVectorizations(@Param("staleBefore") LocalDateTime staleBefore, Pageable pageable);

    /**
     * 把卡住的向量化改回待处理并累加重试次数。条件里带上扫描时看到的心跳，扫描后有进展或已被其他实例处理时影响行数为 0
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FileUpload f SET f.vectorizationStatus = 'PENDING', f.retryCount = f.retryCount + 1, " +
            "f.processingStartedAt = null, f.heartbeatAt = :now " +
            "WHERE f.id = :id AND f.vectorizationStatus = 'PROCESSING' " +
            "AND ((:observedHeartbeat IS NULL AND f.heartbeatAt IS NULL) OR f.heartbeatAt = :observedHeartbeat)")
    int requeueStaleVectorization(@Param("id") Long id,
                                  @Param("observedHeartbeat") LocalDateTime observedHeartbeat,
                                  @Param("now") LocalDateTime now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FileUpload f SET f.vectorizationStatus = 'FAILED', f.vectorizationErrorMessage = :errorMessage, " +
            "f.processingStartedAt = null, f.heartbeatAt = :now " +
            "WHERE f.id = :id AND f.vectorizationStatus = 'PROCESSING' " +
            "AND ((:observedHeartbeat IS NULL AND f.heartbeatAt IS NULL) OR f.heartbeatAt = :observedHeartbeat)")
    int failStaleVectorization(@Param("id") Long id,
                               @Param("observedHeartbeat") LocalDateTime observedHeartbeat,
                               @Param("errorMessage") String errorMessage,
                               @Param("now") LocalDateTime now);

    /**
     * 长时间停在某个上传状态（上传中 / 合并中）且没有心跳的记录
     */
    @Query("SELECT f FROM FileUpload f WHERE f.status = :status " +
            "AND (f.heartbeatAt < :staleBefore OR (f.heartbeatAt IS NULL AND f.createdAt < :staleBefore)) ORDER BY f.id")
    List<FileUpload> findStaleByUploadStatus(@Param("status") int status,
                                             @Param("staleBefore") LocalDateTime staleBefore,
                                             Pageable pageable);

    /**
     * 卡住的合并改回上传中，用户可以重新发起合并；分片在合并成功前不会删除
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FileUpload f SET f.status = :uploadingStatus, f.heartbeatAt = :now " +
            "WHERE f.id = :id AND f.status = :mergingStatus " +
            "AND ((:observedHeartbeat IS NULL AND f.heartbeatAt IS NULL) OR f.heartbeatAt = :observedHeartbeat)")
    int resetStaleMerge(@Param("id") Long id,
                        @Param("mergingStatus") int mergingStatus,
                        @Param("uploadingStatus") int uploadingStatus,
                        @Param("observedHeartbeat") LocalDateTime observedHeartbeat,
                        @Param("now") LocalDateTime now);

    /**
     * 删除废弃的上传记录。条件里带上扫描时看到的心跳，扫描后又有分片到达时不删
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM FileUpload f WHERE f.id = :id AND f.status = :uploadingStatus " +
            "AND ((:observedHeartbeat IS NULL AND f.heartbeatAt IS NULL) OR f.heartbeatAt = :observedHeartbeat)")
    int deleteAbandonedUpload(@Param("id") Long id,
                              @Param("uploadingStatus") int uploadingStatus,
                              @Param("observedHeartbeat") LocalDateTime observedHeartbeat);
}
