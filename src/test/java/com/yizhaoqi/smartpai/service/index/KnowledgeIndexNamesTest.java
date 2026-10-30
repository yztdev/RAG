package com.yizhaoqi.smartpai.service.index;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeIndexNamesTest {

    @Test
    void versionedIndexNameIsDeterministicAndEsSafe() {
        assertEquals("knowledge_base_aliyun_text-embedding-v4_2048",
                KnowledgeIndexNames.versionedIndexName("aliyun:text-embedding-v4:2048"));
        assertEquals("knowledge_base_zhipu_embedding-3_2048",
                KnowledgeIndexNames.versionedIndexName("Zhipu:Embedding-3:2048"));
        assertEquals("knowledge_base_a_b_c", KnowledgeIndexNames.versionedIndexName("a/b *c?"));
    }

    @Test
    void rejectsBlankModelVersion() {
        assertThrows(IllegalArgumentException.class, () -> KnowledgeIndexNames.versionedIndexName(" "));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeIndexNames.versionedIndexName(":::"));
    }
}
