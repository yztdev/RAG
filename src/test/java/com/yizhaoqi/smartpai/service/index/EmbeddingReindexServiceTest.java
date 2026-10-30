package com.yizhaoqi.smartpai.service.index;

import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.config.ContextualRetrievalProperties;
import com.yizhaoqi.smartpai.config.EmbeddingReindexProperties;
import com.yizhaoqi.smartpai.consumer.FileProcessingListenerGate;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.ElasticsearchService;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService.ActiveProviderView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingReindexServiceTest {

    private static final ActiveProviderView OLD = new ActiveProviderView("aliyun", "阿里云", "openai-compatible",
            "https://dashscope", "text-embedding-v3", "k1", 1024);
    private static final ActiveProviderView NEW = new ActiveProviderView("zhipu", "智谱", "openai-compatible",
            "https://zhipu", "embedding-3", "k2", 2048);
    private static final String OLD_INDEX = "knowledge_base_aliyun_text-embedding-v3_1024";
    private static final String NEW_INDEX = "knowledge_base_zhipu_embedding-3_2048";
    private static final String NEW_VERSION = "zhipu:embedding-3:2048";

    private final KnowledgeIndexManager indexManager = mock(KnowledgeIndexManager.class);
    private final ElasticsearchService esService = mock(ElasticsearchService.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final ModelProviderConfigService providerConfig = mock(ModelProviderConfigService.class);
    private final FileUploadRepository fileUploadRepository = mock(FileUploadRepository.class);
    private final DocumentVectorRepository documentVectorRepository = mock(DocumentVectorRepository.class);
    private final FileProcessingListenerGate gate = mock(FileProcessingListenerGate.class);
    private final EmbeddingReindexProperties properties = new EmbeddingReindexProperties();
    private final ContextualRetrievalProperties contextualProperties = new ContextualRetrievalProperties();
    private EmbeddingReindexService service;

    @BeforeEach
    void setUp() throws Exception {
        properties.setBatchSize(2);
        service = new EmbeddingReindexService(indexManager, esService, embeddingClient, providerConfig,
                fileUploadRepository, documentVectorRepository, gate, properties, contextualProperties,
                new DirectExecutorService());

        when(providerConfig.getProvider(ModelProviderConfigService.SCOPE_EMBEDDING, "zhipu")).thenReturn(NEW);
        when(providerConfig.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING)).thenReturn(OLD);
        when(indexManager.currentAliasTarget()).thenReturn(Optional.of(OLD_INDEX));
        when(indexManager.indexExists(NEW_INDEX)).thenReturn(false, true);

        when(fileUploadRepository.findAll()).thenReturn(List.of(
                upload("f1", FileUpload.VECTORIZATION_STATUS_COMPLETED),
                upload("f2", null),
                upload("failed", FileUpload.VECTORIZATION_STATUS_FAILED)
        ));
        List<DocumentVector> f1Chunks = List.of(chunk("f1", 0), chunk("f1", 1), chunk("f1", 2));
        when(documentVectorRepository.findByFileMd5OrderByChunkIdAsc("f1")).thenReturn(f1Chunks);
        when(documentVectorRepository.findByFileMd5OrderByChunkIdAsc("f2")).thenReturn(List.of());
        when(documentVectorRepository.countByFileMd5("f1")).thenReturn(3L);
        when(embeddingClient.embedWithProvider(anyList(), eq("system-reindex"), any(), eq(NEW))).thenAnswer(invocation -> {
            List<?> texts = invocation.getArgument(0);
            return new EmbeddingClient.EmbeddingUsageResult(
                    texts.stream().map(t -> new float[]{1f, 2f}).toList(), 10, NEW_VERSION);
        });
        // 第一轮时新索引里没有 f1，写入后后续轮次视为已完成
        when(esService.countByFileMd5(NEW_INDEX, "f1")).thenReturn(0L, 3L);
        when(esService.countByFileMd5AndModelVersion(NEW_INDEX, "f1", NEW_VERSION)).thenReturn(0L, 3L);
        when(indexManager.count(NEW_INDEX)).thenReturn(3L);
    }

    @Test
    void rebuildsIntoNewIndexValidatesThenSwitchesAliasAndProvider() throws Exception {
        EmbeddingReindexService.RebuildStatus submitted = service.startRebuild("zhipu", false, "admin");
        assertEquals(NEW_INDEX, submitted.targetIndex());

        EmbeddingReindexService.RebuildStatus status = service.getStatus().orElseThrow();
        assertEquals(EmbeddingReindexService.RebuildState.SUCCEEDED, status.state(), status.message());
        assertEquals(OLD_INDEX, status.previousIndex());
        assertEquals(3, status.expectedChunks());
        assertEquals(3, status.actualChunks());

        ArgumentCaptor<Map<String, String>> meta = ArgumentCaptor.forClass(Map.class);
        verify(indexManager).createIndex(eq(NEW_INDEX), eq(2048), meta.capture(), eq(false));
        assertEquals("zhipu", meta.getValue().get(KnowledgeIndexManager.META_PROVIDER));
        assertEquals(NEW_VERSION, meta.getValue().get(KnowledgeIndexManager.META_MODEL_VERSION));

        // 3 个分块按 batchSize=2 分两批，拿到向量立即写入，文档 ID 可重复写入（幂等）
        ArgumentCaptor<List<EsDocument>> batches = ArgumentCaptor.forClass(List.class);
        verify(esService, times(2)).bulkIndex(eq(NEW_INDEX), batches.capture());
        assertEquals(List.of(2, 1), batches.getAllValues().stream().map(List::size).toList());
        EsDocument first = batches.getAllValues().get(0).get(0);
        assertEquals("f1_0", first.getId());
        assertEquals(NEW_VERSION, first.getModelVersion());
        assertEquals("u1", first.getUserId());
        assertEquals("org", first.getOrgTag());
        verify(esService, never()).bulkIndex(anyList());

        InOrder order = inOrder(gate, indexManager, providerConfig);
        order.verify(gate).pause(FileProcessingListenerGate.REASON_INDEX_SWITCH);
        order.verify(indexManager).switchAlias(NEW_INDEX);
        order.verify(providerConfig).activateEmbeddingProviderAfterReindex("zhipu", "admin");
        order.verify(gate).resume(FileProcessingListenerGate.REASON_INDEX_SWITCH);
        verify(indexManager, never()).deleteIndex(anyString());
    }

    @Test
    void rebuildReusesStoredChunkContextsWithSameCompositionAsIngestion() throws Exception {
        contextualProperties.setEnabled(true);
        List<DocumentVector> f1Chunks = List.of(chunk("f1", 0), chunk("f1", 1), chunk("f1", 2));
        f1Chunks.get(0).setChunkContext("出自《部署手册》第 2 章端口配置。");
        when(documentVectorRepository.findByFileMd5OrderByChunkIdAsc("f1")).thenReturn(f1Chunks);

        service.startRebuild("zhipu", false, "admin");

        ArgumentCaptor<List<String>> texts = ArgumentCaptor.forClass(List.class);
        verify(embeddingClient, times(2)).embedWithProvider(texts.capture(), eq("system-reindex"), any(), eq(NEW));
        assertEquals(List.of("出自《部署手册》第 2 章端口配置。\n\ntext-0", "text-1"), texts.getAllValues().get(0),
                "说明拼在原文前面做 embedding，没有说明的分块按原文");
        ArgumentCaptor<List<EsDocument>> batches = ArgumentCaptor.forClass(List.class);
        verify(esService, times(2)).bulkIndex(eq(NEW_INDEX), batches.capture());
        EsDocument first = batches.getAllValues().get(0).get(0);
        assertEquals("text-0", first.getTextContent(), "ES 里原文字段不带说明");
        assertEquals("出自《部署手册》第 2 章端口配置。", first.getChunkContext());
    }

    @Test
    void rebuildIgnoresStoredContextsWhenContextualRetrievalDisabled() throws Exception {
        List<DocumentVector> f1Chunks = List.of(chunk("f1", 0), chunk("f1", 1), chunk("f1", 2));
        f1Chunks.get(0).setChunkContext("旧说明");
        when(documentVectorRepository.findByFileMd5OrderByChunkIdAsc("f1")).thenReturn(f1Chunks);

        service.startRebuild("zhipu", false, "admin");

        ArgumentCaptor<List<String>> texts = ArgumentCaptor.forClass(List.class);
        verify(embeddingClient, times(2)).embedWithProvider(texts.capture(), eq("system-reindex"), any(), eq(NEW));
        assertEquals(List.of("text-0", "text-1"), texts.getAllValues().get(0));
        ArgumentCaptor<List<EsDocument>> batches = ArgumentCaptor.forClass(List.class);
        verify(esService, times(2)).bulkIndex(eq(NEW_INDEX), batches.capture());
        assertEquals(null, batches.getAllValues().get(0).get(0).getChunkContext());
    }

    @Test
    void countMismatchAbortsWithoutSwitchingAlias() throws Exception {
        when(indexManager.count(NEW_INDEX)).thenReturn(5L);

        service.startRebuild("zhipu", false, "admin");

        EmbeddingReindexService.RebuildStatus status = service.getStatus().orElseThrow();
        assertEquals(EmbeddingReindexService.RebuildState.FAILED, status.state());
        assertTrue(status.message().contains("文档数校验失败"));
        verify(indexManager, never()).switchAlias(anyString());
        verify(providerConfig, never()).activateEmbeddingProviderAfterReindex(anyString(), anyString());
        verify(gate).resume(FileProcessingListenerGate.REASON_INDEX_SWITCH);
    }

    @Test
    void validationQueryWithoutHitsAbortsSwitch() throws Exception {
        properties.setValidationQueries(List.of("RAG知识库支持哪些文件格式"));
        when(indexManager.knnProbe(eq(NEW_INDEX), anyList(), anyInt())).thenReturn(0);

        service.startRebuild("zhipu", false, "admin");

        assertEquals(EmbeddingReindexService.RebuildState.FAILED, service.getStatus().orElseThrow().state());
        verify(indexManager, never()).switchAlias(anyString());
    }

    @Test
    void providerActivationFailureRollsAliasBack() throws Exception {
        doThrow(new RuntimeException("db down")).when(providerConfig).activateEmbeddingProviderAfterReindex("zhipu", "admin");

        service.startRebuild("zhipu", false, "admin");

        assertEquals(EmbeddingReindexService.RebuildState.FAILED, service.getStatus().orElseThrow().state());
        InOrder order = inOrder(indexManager);
        order.verify(indexManager).switchAlias(NEW_INDEX);
        order.verify(indexManager).switchAlias(OLD_INDEX);
    }

    @Test
    void legacyConcreteIndexIsBackedUpBeforeFirstAliasSwitch() throws Exception {
        when(indexManager.currentAliasTarget()).thenReturn(Optional.empty());
        when(indexManager.hasLegacyConcreteIndex()).thenReturn(true);
        when(indexManager.backupLegacyIndex(anyMap())).thenReturn("knowledge_base_legacy_1");

        service.startRebuild("zhipu", false, "admin");

        EmbeddingReindexService.RebuildStatus status = service.getStatus().orElseThrow();
        assertEquals(EmbeddingReindexService.RebuildState.SUCCEEDED, status.state(), status.message());
        assertEquals("knowledge_base_legacy_1", status.previousIndex());
        InOrder order = inOrder(indexManager);
        order.verify(indexManager).backupLegacyIndex(anyMap());
        order.verify(indexManager).switchAlias(NEW_INDEX);
    }

    @Test
    void alreadyCompleteFilesAreSkippedOnRerun() throws Exception {
        when(indexManager.indexExists(NEW_INDEX)).thenReturn(true);
        when(esService.countByFileMd5(NEW_INDEX, "f1")).thenReturn(3L);
        when(esService.countByFileMd5AndModelVersion(NEW_INDEX, "f1", NEW_VERSION)).thenReturn(3L);

        service.startRebuild("zhipu", false, "admin");

        assertEquals(EmbeddingReindexService.RebuildState.SUCCEEDED, service.getStatus().orElseThrow().state());
        verify(indexManager, never()).createIndex(anyString(), anyInt(), anyMap(), anyBoolean());
        verify(esService, never()).bulkIndex(anyString(), anyList());
    }

    @Test
    void freshRebuildDropsExistingTargetIndex() throws Exception {
        when(indexManager.indexExists(NEW_INDEX)).thenReturn(true, false, true);

        service.startRebuild("zhipu", true, "admin");

        verify(indexManager).deleteIndex(NEW_INDEX);
        verify(indexManager).createIndex(eq(NEW_INDEX), eq(2048), anyMap(), eq(false));
    }

    @Test
    void rejectsRebuildIntoLiveIndex() throws Exception {
        when(indexManager.currentAliasTarget()).thenReturn(Optional.of(NEW_INDEX));

        CustomException error = assertThrows(CustomException.class, () -> service.startRebuild("zhipu", false, "admin"));
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
    }

    @Test
    void rollbackSwitchesAliasAndProviderWhenModelStillMatches() throws Exception {
        when(indexManager.indexExists(OLD_INDEX)).thenReturn(true);
        when(indexManager.readMeta(OLD_INDEX)).thenReturn(Map.of(
                KnowledgeIndexManager.META_PROVIDER, "aliyun",
                KnowledgeIndexManager.META_MODEL_VERSION, "aliyun:text-embedding-v3:1024"));
        when(providerConfig.getProvider(ModelProviderConfigService.SCOPE_EMBEDDING, "aliyun")).thenReturn(OLD);

        service.rollback(OLD_INDEX, "admin");

        verify(indexManager).switchAlias(OLD_INDEX);
        verify(providerConfig).activateEmbeddingProviderAfterReindex("aliyun", "admin");
    }

    @Test
    void rollbackRefusesWhenProviderConfigNoLongerMatchesIndex() throws Exception {
        when(indexManager.indexExists(OLD_INDEX)).thenReturn(true);
        when(indexManager.readMeta(OLD_INDEX)).thenReturn(Map.of(
                KnowledgeIndexManager.META_PROVIDER, "aliyun",
                KnowledgeIndexManager.META_MODEL_VERSION, "aliyun:text-embedding-v2:1536"));
        when(providerConfig.getProvider(ModelProviderConfigService.SCOPE_EMBEDDING, "aliyun")).thenReturn(OLD);

        assertThrows(CustomException.class, () -> service.rollback(OLD_INDEX, "admin"));
        verify(indexManager, never()).switchAlias(anyString());
    }

    private static FileUpload upload(String md5, String vectorizationStatus) {
        FileUpload upload = new FileUpload();
        upload.setFileMd5(md5);
        upload.setStatus(FileUpload.STATUS_COMPLETED);
        upload.setVectorizationStatus(vectorizationStatus);
        return upload;
    }

    private static DocumentVector chunk(String md5, int chunkId) {
        DocumentVector vector = new DocumentVector();
        vector.setFileMd5(md5);
        vector.setChunkId(chunkId);
        vector.setTextContent("text-" + chunkId);
        vector.setUserId("u1");
        vector.setOrgTag("org");
        vector.setPublic(false);
        return vector;
    }

    /**
     * 在调用线程上同步执行，便于断言重建结果
     */
    private static final class DirectExecutorService extends AbstractExecutorService {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
