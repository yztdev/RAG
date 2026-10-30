package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.FileProcessingDeadLetter;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileProcessingDeadLetterRepository extends JpaRepository<FileProcessingDeadLetter, Long> {

    boolean existsBySourceTopicAndSourcePartitionAndSourceOffset(String sourceTopic, int sourcePartition, long sourceOffset);
}
