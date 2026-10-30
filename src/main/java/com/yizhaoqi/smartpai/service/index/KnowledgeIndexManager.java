package com.yizhaoqi.smartpai.service.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.IndexState;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 知识库物理索引与别名的生命周期管理：建版本索引、原子切换别名、旧版同名物理索引迁移。
 */
@Component
public class KnowledgeIndexManager {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeIndexManager.class);
    private static final long REINDEX_POLL_INTERVAL_MILLIS = 2000;

    public static final String META_PROVIDER = "embeddingProvider";
    public static final String META_MODEL_VERSION = "modelVersion";
    public static final String META_CREATED_AT = "createdAt";

    private final ElasticsearchClient esClient;
    private final Resource mappingResource;
    private final ObjectMapper objectMapper;

    public KnowledgeIndexManager(ElasticsearchClient esClient,
                                 @Value("classpath:es-mappings/knowledge_base.json") Resource mappingResource,
                                 ObjectMapper objectMapper) {
        this.esClient = esClient;
        this.mappingResource = mappingResource;
        this.objectMapper = objectMapper;
    }

    /**
     * @return 别名当前指向的物理索引；别名不存在（全新环境或旧版同名物理索引）时为空
     */
    public Optional<String> currentAliasTarget() throws IOException {
        if (!esClient.indices().existsAlias(e -> e.name(KnowledgeIndexNames.ALIAS)).value()) {
            return Optional.empty();
        }
        return esClient.indices().getAlias(g -> g.name(KnowledgeIndexNames.ALIAS)).result().keySet().stream()
                .sorted()
                .findFirst();
    }

    /**
     * 旧版本直接创建了名为 knowledge_base 的物理索引，需要在第一次切换别名时迁移
     */
    public boolean hasLegacyConcreteIndex() throws IOException {
        boolean nameExists = esClient.indices().exists(e -> e.index(KnowledgeIndexNames.ALIAS)).value();
        return nameExists && !esClient.indices().existsAlias(e -> e.name(KnowledgeIndexNames.ALIAS)).value();
    }

    public boolean indexExists(String index) throws IOException {
        return esClient.indices().exists(e -> e.index(index)).value();
    }

    /**
     * 以 es-mappings/knowledge_base.json 为模板建索引，向量维度按目标模型覆盖，并把模型信息写入 mapping _meta
     */
    public void createIndex(String index, int dims, Map<String, String> meta, boolean attachAlias) throws IOException {
        String mappingJson = buildMappingJson(dims, meta);
        esClient.indices().create(c -> {
            c.index(index).withJson(new StringReader(mappingJson));
            if (attachAlias) {
                c.aliases(KnowledgeIndexNames.ALIAS, a -> a);
            }
            return c;
        });
        logger.info("已创建知识库索引: index={}, dims={}, alias={}, meta={}", index, dims, attachAlias, meta);
    }

    String buildMappingJson(int dims, Map<String, String> meta) throws IOException {
        ObjectNode root = readTemplate();
        ObjectNode mappings = (ObjectNode) root.path("mappings");
        ((ObjectNode) mappings.path("properties").path("vector")).put("dims", dims);
        mappings.set("_meta", objectMapper.valueToTree(meta));
        return objectMapper.writeValueAsString(root);
    }

    /**
     * 把模板里新增、而已有索引还没有的字段补进 mapping（ES 允许给已有索引追加新字段，不需要重建）。
     * 只追加缺失字段、不改已有字段，重复调用是空操作。例如上下文检索的 chunkContext 字段：
     * 必须在第一次写入之前补上，否则 ES 会按动态映射用 standard 分词器建字段，BM25 就不走 IK 分词了。
     *
     * @return 本次补上的字段名
     */
    public List<String> ensureTemplateFields(String index) throws IOException {
        Map<String, Property> existing = esClient.indices().getMapping(g -> g.index(index)).result().values().stream()
                .findFirst()
                .map(record -> record.mappings().properties())
                .orElseThrow(() -> new IllegalStateException("读取索引 mapping 失败: " + index));
        ObjectNode missing = missingTemplateFields(existing.keySet());
        if (missing.isEmpty()) {
            return List.of();
        }
        String body = objectMapper.writeValueAsString(objectMapper.createObjectNode().set("properties", missing));
        esClient.indices().putMapping(p -> p.index(index).withJson(new StringReader(body)));
        List<String> added = new ArrayList<>();
        missing.fieldNames().forEachRemaining(added::add);
        logger.warn("已为知识库索引追加 mapping 字段: index={}, fields={}", index, added);
        return added;
    }

    /**
     * 模板里有、已有索引里没有的字段定义。vector 的维度随模型变化，不参与追加。
     */
    ObjectNode missingTemplateFields(Set<String> existingFields) throws IOException {
        ObjectNode templateProperties = (ObjectNode) readTemplate().path("mappings").path("properties");
        ObjectNode missing = objectMapper.createObjectNode();
        templateProperties.fields().forEachRemaining(field -> {
            if (!existingFields.contains(field.getKey()) && !"vector".equals(field.getKey())) {
                missing.set(field.getKey(), field.getValue());
            }
        });
        return missing;
    }

    private ObjectNode readTemplate() throws IOException {
        try (var inputStream = mappingResource.getInputStream()) {
            return (ObjectNode) objectMapper.readTree(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    public void deleteIndex(String index) throws IOException {
        if (KnowledgeIndexNames.ALIAS.equals(index)) {
            throw new IllegalArgumentException("禁止直接删除别名或旧版同名索引");
        }
        esClient.indices().delete(d -> d.index(index));
        logger.warn("已删除知识库索引: {}", index);
    }

    public void refresh(String index) throws IOException {
        esClient.indices().refresh(r -> r.index(index));
    }

    public long count(String index) throws IOException {
        return esClient.count(c -> c.index(index)).count();
    }

    public Map<String, String> readMeta(String index) throws IOException {
        Map<String, String> meta = new LinkedHashMap<>();
        TypeMapping mapping = esClient.indices().getMapping(g -> g.index(index)).result().values().stream()
                .findFirst()
                .map(record -> record.mappings())
                .orElse(null);
        if (mapping != null && mapping.meta() != null) {
            for (Map.Entry<String, JsonData> entry : mapping.meta().entrySet()) {
                meta.put(entry.getKey(), entry.getValue().to(String.class));
            }
        }
        return meta;
    }

    /**
     * 原子切换别名：一次 _aliases 请求内移除旧指向并指向新索引，检索不会出现"半新半旧"。
     * 旧版同名物理索引会在同一请求里被移除（调用方需先用 {@link #backupLegacyIndex} 备份）。
     */
    public void switchAlias(String targetIndex) throws IOException {
        if (!indexExists(targetIndex)) {
            throw new IllegalStateException("目标索引不存在: " + targetIndex);
        }
        // 回滚目标可能是加字段之前建的旧索引，切过去之前先补齐 mapping，避免新字段被动态映射
        ensureTemplateFields(targetIndex);
        List<Action> actions = new ArrayList<>();
        if (hasLegacyConcreteIndex()) {
            actions.add(Action.of(a -> a.removeIndex(r -> r.index(KnowledgeIndexNames.ALIAS))));
        } else if (esClient.indices().existsAlias(e -> e.name(KnowledgeIndexNames.ALIAS)).value()) {
            for (String index : esClient.indices().getAlias(g -> g.name(KnowledgeIndexNames.ALIAS)).result().keySet()) {
                actions.add(Action.of(a -> a.remove(r -> r.index(index).alias(KnowledgeIndexNames.ALIAS))));
            }
        }
        actions.add(Action.of(a -> a.add(ad -> ad.index(targetIndex).alias(KnowledgeIndexNames.ALIAS))));
        esClient.indices().updateAliases(u -> u.actions(actions));
        logger.warn("知识库别名 {} 已原子切换到 {}", KnowledgeIndexNames.ALIAS, targetIndex);
    }

    /**
     * 把旧版同名物理索引完整复制到 knowledge_base_legacy_{ts}，作为别名迁移后的回滚目标
     */
    public String backupLegacyIndex(Map<String, String> meta) throws IOException, InterruptedException {
        String backupIndex = KnowledgeIndexNames.legacyBackupIndexName(System.currentTimeMillis());
        int dims = readVectorDims(KnowledgeIndexNames.ALIAS);
        createIndex(backupIndex, dims, meta, false);

        String taskId = esClient.reindex(r -> r
                .source(s -> s.index(KnowledgeIndexNames.ALIAS))
                .dest(d -> d.index(backupIndex))
                .waitForCompletion(false)).task();
        // reindex 可能很慢，异步提交后轮询任务状态，避免客户端 socket 超时
        while (!esClient.tasks().get(t -> t.taskId(taskId)).completed()) {
            Thread.sleep(REINDEX_POLL_INTERVAL_MILLIS);
        }
        refresh(backupIndex);
        long source = count(KnowledgeIndexNames.ALIAS);
        long copied = count(backupIndex);
        if (source != copied) {
            throw new IllegalStateException(String.format("旧索引备份文档数不一致: source=%d, backup=%d", source, copied));
        }
        logger.warn("旧版同名索引已备份: {} -> {}, docs={}", KnowledgeIndexNames.ALIAS, backupIndex, copied);
        return backupIndex;
    }

    public int readVectorDims(String index) throws IOException {
        TypeMapping mapping = esClient.indices().getMapping(g -> g.index(index)).result().values().stream()
                .findFirst()
                .map(record -> record.mappings())
                .orElseThrow(() -> new IllegalStateException("读取索引 mapping 失败: " + index));
        Property vector = mapping.properties().get("vector");
        if (vector == null || !vector.isDenseVector()) {
            throw new IllegalStateException("索引缺少 dense_vector 字段: " + index);
        }
        return vector.denseVector().dims();
    }

    /**
     * 在指定索引上做一次 KNN 试探检索，返回命中条数（用于切换前抽样验证新索引可用）
     */
    public int knnProbe(String index, List<Float> queryVector, int k) throws IOException {
        return esClient.search(s -> s
                        .index(index)
                        .source(src -> src.fetch(false))
                        .knn(kn -> kn.field("vector").queryVector(queryVector).k(k).numCandidates(Math.max(k * 10L, 50L)))
                        .size(k),
                Void.class).hits().hits().size();
    }

    /**
     * 列出所有知识库版本索引及其模型信息、文档数、是否为线上索引
     */
    public List<IndexInfo> listIndices() throws IOException {
        Map<String, IndexState> states = esClient.indices()
                .get(g -> g.index(KnowledgeIndexNames.VERSIONED_PREFIX + "*").allowNoIndices(true))
                .result();
        List<IndexInfo> infos = new ArrayList<>();
        for (Map.Entry<String, IndexState> entry : states.entrySet()) {
            String index = entry.getKey();
            boolean live = entry.getValue().aliases() != null
                    && entry.getValue().aliases().containsKey(KnowledgeIndexNames.ALIAS);
            infos.add(new IndexInfo(index, live, readMeta(index), count(index)));
        }
        infos.sort(Comparator.comparing(IndexInfo::name));
        return infos;
    }

    public record IndexInfo(String name, boolean live, Map<String, String> meta, long docCount) {
    }
}
