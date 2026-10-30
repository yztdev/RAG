package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.DocumentVector;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DocumentVectorRepository extends JpaRepository<DocumentVector, Long> {
    List<DocumentVector> findByFileMd5(String fileMd5); // 查询某文件的所有分块

    List<DocumentVector> findByFileMd5OrderByChunkIdAsc(String fileMd5); // 按分块顺序查询某文件的所有分块

    // 分页读取分块，向量化时一批一批处理，不把整份文件的分块一次性读进内存
    Slice<DocumentVector> findByFileMd5OrderByChunkIdAsc(String fileMd5, Pageable pageable);

    // 按 chunkId 区间读取相邻分块：上下文检索取文档窗口、父子检索取父块都用它，避免整份文件读进内存
    List<DocumentVector> findByFileMd5AndChunkIdBetweenOrderByChunkIdAsc(String fileMd5, Integer fromChunkId, Integer toChunkId);

    long countByFileMd5(String fileMd5);

    long countByFileMd5AndPageNumberIsNotNull(String fileMd5);
    
    /**
     * 删除指定文件MD5的所有文档向量记录
     * 
     * @param fileMd5 文件MD5
     */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM document_vectors WHERE file_md5 = ?1", nativeQuery = true)
    void deleteByFileMd5(String fileMd5);
}
