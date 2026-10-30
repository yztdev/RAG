package com.yizhaoqi.smartpai.service.index;

import java.util.Locale;

/**
 * 知识库索引命名约定：
 * 业务代码一律读写别名 {@link #ALIAS}，别名指向带模型版本的物理索引（如 knowledge_base_aliyun_text-embedding-v4_2048）。
 * embedding 模型升级时新建物理索引，校验通过后原子切换别名，旧索引保留用于回滚。
 */
public final class KnowledgeIndexNames {

    public static final String ALIAS = "knowledge_base";
    public static final String VERSIONED_PREFIX = ALIAS + "_";
    public static final String LEGACY_BACKUP_PREFIX = VERSIONED_PREFIX + "legacy_";
    /** 覆盖别名、所有版本索引以及旧版本的同名物理索引 */
    public static final String ALL_INDICES_PATTERN = ALIAS + "*";

    private KnowledgeIndexNames() {
    }

    /**
     * 按 embedding 模型版本生成物理索引名。同一模型版本得到同一个索引名，重建任务中途失败后重跑可以续建。
     */
    public static String versionedIndexName(String modelVersion) {
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion 不能为空");
        }
        String slug = modelVersion.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^[_\\-]+|[_\\-]+$", "");
        if (slug.isEmpty()) {
            throw new IllegalArgumentException("无法从 modelVersion 生成索引名: " + modelVersion);
        }
        return VERSIONED_PREFIX + slug;
    }

    public static String legacyBackupIndexName(long timestampMillis) {
        return LEGACY_BACKUP_PREFIX + timestampMillis;
    }
}
