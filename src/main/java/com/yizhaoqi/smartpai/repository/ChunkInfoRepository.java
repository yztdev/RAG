package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.ChunkInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 分片记录按 (userId, fileMd5) 隔离，两个用户同时上传同一个文件时互不影响
 */
public interface ChunkInfoRepository extends JpaRepository<ChunkInfo, Long> {
    List<ChunkInfo> findByFileMd5AndUserIdOrderByChunkIndexAsc(String fileMd5, String userId);

    boolean existsByFileMd5AndUserIdAndChunkIndex(String fileMd5, String userId, int chunkIndex);

    /**
     * 删除文件所有用户的分片记录，仅用于整份文档删除
     */
    @Transactional
    @Modifying
    @Query("delete from ChunkInfo c where c.fileMd5 = :fileMd5")
    int deleteByFileMd5(@Param("fileMd5") String fileMd5);

    @Transactional
    @Modifying
    @Query("delete from ChunkInfo c where c.fileMd5 = :fileMd5 and c.userId = :userId")
    int deleteByFileMd5AndUserId(@Param("fileMd5") String fileMd5, @Param("userId") String userId);

    @Transactional
    @Modifying
    @Query("delete from ChunkInfo c where c.fileMd5 = :fileMd5 and c.userId = :userId and c.chunkIndex = :chunkIndex")
    int deleteByFileMd5AndUserIdAndChunkIndex(@Param("fileMd5") String fileMd5,
                                              @Param("userId") String userId,
                                              @Param("chunkIndex") int chunkIndex);

    @Query("select c.chunkIndex from ChunkInfo c where c.fileMd5 = :fileMd5 and c.userId = :userId order by c.chunkIndex asc")
    List<Integer> findChunkIndexesByFileMd5AndUserId(@Param("fileMd5") String fileMd5, @Param("userId") String userId);
}
