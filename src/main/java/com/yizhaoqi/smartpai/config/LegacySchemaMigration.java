package com.yizhaoqi.smartpai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Hibernate ddl-auto=update 只会新增列和约束，不会删除旧约束，这里补上老库升级需要的两步：
 * 1. 删除 chunk_info 旧的 (file_md5, chunk_index) 唯一键，否则两个用户上传同一个文件仍会互相冲突；
 * 2. 给升级前遗留的分片记录回填 user_id。
 * 另外检查 document_vectors 的 (file_md5, chunk_id) 唯一键是否建成功，有重复数据建不上时提示执行迁移脚本。
 * 只处理 MySQL，全部操作可重复执行
 */
@Component
@Order(0)
public class LegacySchemaMigration implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(LegacySchemaMigration.class);
    private static final String MIGRATION_SCRIPT = "docs/databases/migrations/2026-10-06-file-processing-reliability.sql";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public LegacySchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!isMySql()) {
                return;
            }
            dropLegacyChunkUniqueKey();
            backfillChunkUserId();
            checkDocumentVectorUniqueKey();
        } catch (Exception e) {
            logger.warn("旧表结构升级检查失败，请手动执行 {}: {}", MIGRATION_SCRIPT, e.getMessage());
        }
    }

    private void dropLegacyChunkUniqueKey() {
        if (indexExists("chunk_info", "uk_file_md5_chunk_index")) {
            jdbcTemplate.execute("ALTER TABLE chunk_info DROP INDEX uk_file_md5_chunk_index");
            logger.info("已删除 chunk_info 旧唯一键 uk_file_md5_chunk_index，分片记录改为按用户隔离");
        }
    }

    /**
     * 升级前的分片记录没有 user_id（加列时填成空串），按仍在上传 / 合并中的文件记录回填
     */
    private void backfillChunkUserId() {
        int updated = jdbcTemplate.update("""
                UPDATE chunk_info c
                JOIN (SELECT file_md5, MIN(user_id) AS user_id FROM file_upload WHERE status IN (0, 2) GROUP BY file_md5) f
                  ON c.file_md5 = f.file_md5
                SET c.user_id = f.user_id
                WHERE c.user_id IS NULL OR c.user_id = ''
                """);
        if (updated > 0) {
            logger.info("已为 {} 条旧分片记录回填 user_id", updated);
        }
    }

    private void checkDocumentVectorUniqueKey() {
        if (indexExists("document_vectors", "uk_document_vectors_md5_chunk")) {
            return;
        }
        Integer duplicates = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM (
                  SELECT file_md5, chunk_id FROM document_vectors GROUP BY file_md5, chunk_id HAVING COUNT(*) > 1
                ) d
                """, Integer.class);
        logger.warn("document_vectors 缺少 (file_md5, chunk_id) 唯一键，存在 {} 组重复分块，请执行 {} 去重后重启",
                duplicates, MIGRATION_SCRIPT);
    }

    private boolean indexExists(String table, String index) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, table, index);
        return count != null && count > 0;
    }

    private boolean isMySql() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql");
        }
    }
}
