package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.FileProcessingOutbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface FileProcessingOutboxRepository extends JpaRepository<FileProcessingOutbox, Long> {

    @Query("SELECT o FROM FileProcessingOutbox o WHERE o.status = 'PENDING' AND o.nextAttemptAt <= :now ORDER BY o.id")
    List<FileProcessingOutbox> findDue(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * 认领一条待投递消息：把下次投递时间推到租约结束。条件里带上读到的旧值，多个实例只有一个能认领成功
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FileProcessingOutbox o SET o.nextAttemptAt = :leaseUntil " +
            "WHERE o.id = :id AND o.status = 'PENDING' AND o.nextAttemptAt = :observedNextAttemptAt")
    int claim(@Param("id") Long id,
              @Param("observedNextAttemptAt") LocalDateTime observedNextAttemptAt,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FileProcessingOutbox o SET o.status = 'SENT', o.sentAt = :now, o.lastError = null, o.attempts = o.attempts + 1 " +
            "WHERE o.id = :id AND o.status = 'PENDING'")
    int markSent(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FileProcessingOutbox o SET o.attempts = o.attempts + 1, o.nextAttemptAt = :nextAttemptAt, o.lastError = :lastError " +
            "WHERE o.id = :id AND o.status = 'PENDING'")
    int markAttemptFailed(@Param("id") Long id,
                          @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                          @Param("lastError") String lastError);

    @Transactional
    @Modifying
    @Query("DELETE FROM FileProcessingOutbox o WHERE o.status = 'SENT' AND o.sentAt < :before")
    int deleteSentBefore(@Param("before") LocalDateTime before);
}
