package com.yizhaoqi.smartpai.service.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class KnowledgeIndexManagerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KnowledgeIndexManager manager = new KnowledgeIndexManager(
            mock(ElasticsearchClient.class), new ClassPathResource("es-mappings/knowledge_base.json"), objectMapper);

    @Test
    void mappingUsesTargetDimensionsAndRecordsModelMeta() throws Exception {
        String json = manager.buildMappingJson(1024, Map.of(
                KnowledgeIndexManager.META_PROVIDER, "zhipu",
                KnowledgeIndexManager.META_MODEL_VERSION, "zhipu:embedding-3:1024"));

        JsonNode mappings = objectMapper.readTree(json).path("mappings");
        assertEquals(1024, mappings.path("properties").path("vector").path("dims").asInt());
        assertEquals("dense_vector", mappings.path("properties").path("vector").path("type").asText());
        assertEquals("zhipu:embedding-3:1024", mappings.path("_meta").path("modelVersion").asText());
        assertEquals("ik_max_word", mappings.path("properties").path("textContent").path("analyzer").asText());
    }

    @Test
    void templateDeclaresChunkContextWithIkAnalyzer() throws Exception {
        JsonNode chunkContext = objectMapper.readTree(manager.buildMappingJson(2048, Map.of()))
                .path("mappings").path("properties").path("chunkContext");
        assertEquals("text", chunkContext.path("type").asText());
        assertEquals("ik_max_word", chunkContext.path("analyzer").asText(),
                "与 textContent 分词器一致，combined_fields 才能把两个字段当成一个字段算 BM25");
        assertEquals("ik_smart", chunkContext.path("search_analyzer").asText());
    }

    @Test
    void onlyMissingNonVectorFieldsAreAppendedToExistingIndex() throws Exception {
        Set<String> oldIndexFields = Set.of("fileMd5", "chunkId", "pageNumber", "anchorText", "textContent",
                "vector", "modelVersion", "userId", "orgTag", "isPublic");

        var missing = manager.missingTemplateFields(oldIndexFields);

        assertEquals(2, missing.size());
        assertTrue(missing.has("chunkContext"));
        assertTrue(missing.has("fileName"));
        assertFalse(manager.missingTemplateFields(Set.of()).has("vector"), "vector 维度随模型变化，不追加");
        var afterUpgrade = new java.util.HashSet<>(oldIndexFields);
        afterUpgrade.add("chunkContext");
        afterUpgrade.add("fileName");
        assertTrue(manager.missingTemplateFields(afterUpgrade).isEmpty(), "重复调用是空操作");
    }

    @Test
    void refusesToDeleteAliasName() {
        assertThrows(IllegalArgumentException.class, () -> manager.deleteIndex(KnowledgeIndexNames.ALIAS));
    }
}
