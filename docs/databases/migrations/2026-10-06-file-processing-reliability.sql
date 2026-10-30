-- 文件处理可靠性升级（上传幂等、分片按用户隔离、卡住任务恢复、outbox、死信落库）
-- 适用于已有数据的 MySQL 库。ddl-auto=update 会自动加列、建新表，应用启动时 LegacySchemaMigration
-- 也会删除 chunk_info 旧唯一键并回填 user_id；这里给出完整的手工版本，所有语句可重复执行前请先备份。

-- 1. file_upload：心跳、认领时间、重试次数
ALTER TABLE file_upload
    ADD COLUMN processing_started_at DATETIME(3) DEFAULT NULL COMMENT '本轮向量化被认领的时间',
    ADD COLUMN heartbeat_at DATETIME(3) DEFAULT NULL COMMENT '当前阶段最近一次进展时间',
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0 COMMENT '卡住后被自动重新投递的次数';
CREATE INDEX idx_vectorization_heartbeat ON file_upload (vectorization_status, heartbeat_at);
CREATE INDEX idx_status_heartbeat ON file_upload (status, heartbeat_at);

-- 2. chunk_info：分片按用户隔离
ALTER TABLE chunk_info ADD COLUMN user_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '上传用户ID' AFTER file_md5;
UPDATE chunk_info c
JOIN (SELECT file_md5, MIN(user_id) AS user_id FROM file_upload WHERE status IN (0, 2) GROUP BY file_md5) f
  ON c.file_md5 = f.file_md5
SET c.user_id = f.user_id
WHERE c.user_id = '';
ALTER TABLE chunk_info DROP INDEX uk_file_md5_chunk_index;
ALTER TABLE chunk_info ADD UNIQUE KEY uk_chunk_user_md5_index (user_id, file_md5, chunk_index);

-- 3. document_vectors：去掉 Kafka 重复投递产生的重复分块（每组保留 vector_id 最小的一行），再加唯一键
--    去重后建议对受影响的文件执行一次"重试向量化"，让 ES 里的分块和 MySQL 对齐
DELETE dv FROM document_vectors dv
JOIN document_vectors keep
  ON dv.file_md5 = keep.file_md5 AND dv.chunk_id = keep.chunk_id AND dv.vector_id > keep.vector_id;
ALTER TABLE document_vectors ADD UNIQUE KEY uk_document_vectors_md5_chunk (file_md5, chunk_id);

-- 4. outbox 与死信表结构见 docs/databases/ddl.sql 中的 file_processing_outbox、file_processing_dead_letter
