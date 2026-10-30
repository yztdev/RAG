package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.DocumentConflict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DocumentConflictRepository extends JpaRepository<DocumentConflict, Long> {

    Page<DocumentConflict> findByStatus(DocumentConflict.Status status, Pageable pageable);

    boolean existsByPairKeyAndStatusIn(String pairKey, Collection<DocumentConflict.Status> statuses);

    @Query("select c from DocumentConflict c where c.status = :status "
            + "and (c.newFileMd5 = :fileMd5 or c.existingFileMd5 = :fileMd5)")
    List<DocumentConflict> findByStatusAndFileMd5(@Param("status") DocumentConflict.Status status,
                                                  @Param("fileMd5") String fileMd5);
}
