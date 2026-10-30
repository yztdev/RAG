package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.service.contextual.ChunkContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorizationServiceTest {

    private static final ModelProviderConfigService.ActiveProviderView PROVIDER = new ModelProviderConfigService.ActiveProviderView(
            "aliyun", "阿里云", "openai-compatible", "https://dashscope", "text-embedding-v4", "k", 2048);
    private static final String MODEL_VERSION = "aliyun:text-embedding-v4:2048";

    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final ElasticsearchService elasticsearchService = mock(ElasticsearchService.class);
    private final DocumentVectorRepository documentVectorRepository = mock(DocumentVectorRepository.class);
    private final ModelProviderConfigService providerConfig = mock(ModelProviderConfigService.class);
    private final ChunkContextService chunkContextService = mock(ChunkContextService.class);
    private final VectorizationService service = new VectorizationService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "embeddingClient", embeddingClient);
        ReflectionTestUtils.setField(service, "elasticsearchService", elasticsearchService);
        ReflectionTestUtils.setField(service, "documentVectorRepository", documentVectorRepository);
        ReflectionTestUtils.setField(service, "modelProviderConfigService", providerConfig);
        ReflectionTestUtils.setField(service, "chunkContextService", chunkContextService);
        ReflectionTestUtils.setField(service, "batchSize", 10);
        when(providerConfig.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING)).thenReturn(PROVIDER);
        when(embeddingClient.embedWithProvider(anyList(), eq("u1"), eq(EmbeddingClient.UsageType.UPLOAD), eq(PROVIDER)))
                .thenAnswer(invocation -> {
                    List<?> texts = invocation.getArgument(0);
                    return new EmbeddingClient.EmbeddingUsageResult(
                            texts.stream().map(t -> new float[]{1f}).toList(), texts.size() * 5, MODEL_VERSION);
                });
    }

    @Test
    void embedsAndIndexesTenChunksAtATimeWithStableIds() {
        stubChunks(25);

        VectorizationService.VectorizationUsageResult result = service.vectorizeWithUsage("md5", "u1", "org", true, "u1");

        assertEquals(25, result.actualChunkCount());
        assertEquals(125, result.actualEmbeddingTokens());
        assertEquals(MODEL_VERSION, result.modelVersion());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EsDocument>> batches = ArgumentCaptor.forClass(List.class);
        verify(elasticsearchService, times(3)).bulkIndex(batches.capture());
        assertEquals(List.of(10, 10, 5), batches.getAllValues().stream().map(List::size).toList());
        EsDocument first = batches.getAllValues().get(0).get(0);
        assertEquals("md5_0", first.getId(), "固定 ID，重试时覆盖而不是重复写入");
        assertEquals(MODEL_VERSION, first.getModelVersion());
        assertEquals("org", first.getOrgTag());
        assertEquals("md5_24", batches.getAllValues().get(2).get(4).getId());

        // 每一批都是"读分块 → embedding → 写 ES"之后才读下一批
        InOrder order = inOrder(documentVectorRepository, embeddingClient, elasticsearchService);
        for (int page = 0; page < 3; page++) {
            order.verify(documentVectorRepository).findByFileMd5OrderByChunkIdAsc(eq("md5"), eq(PageRequest.of(page, 10)));
            order.verify(embeddingClient).embedWithProvider(anyList(), eq("u1"), any(), eq(PROVIDER));
            order.verify(elasticsearchService).bulkIndex(anyList());
        }
        verify(providerConfig, times(1)).getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
    }

    @Test
    void failureStopsBeforeLaterBatches() {
        stubChunks(25);
        when(embeddingClient.embedWithProvider(anyList(), eq("u1"), any(), eq(PROVIDER)))
                .thenReturn(new EmbeddingClient.EmbeddingUsageResult(
                        IntStream.range(0, 10).mapToObj(i -> new float[]{1f}).toList(), 50, MODEL_VERSION))
                .thenThrow(new RuntimeException("embedding down"));

        assertThrows(RuntimeException.class, () -> service.vectorizeWithUsage("md5", "u1", "org", true, "u1"));

        verify(elasticsearchService, times(1)).bulkIndex(anyList());
    }

    @Test
    void contextualRetrievalGeneratesContextsFirstThenEmbedsContextPlusChunk() {
        when(chunkContextService.isEnabled()).thenReturn(true);
        stubChunks(3, "出自《部署手册》端口一节。");

        service.vectorizeWithUsage("md5", "u1", "org", true, "u1");

        InOrder order = inOrder(chunkContextService, documentVectorRepository);
        order.verify(chunkContextService).ensureContexts("md5");
        order.verify(documentVectorRepository).findByFileMd5OrderByChunkIdAsc(eq("md5"), any(Pageable.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> texts = ArgumentCaptor.forClass(List.class);
        verify(embeddingClient).embedWithProvider(texts.capture(), eq("u1"), any(), eq(PROVIDER));
        assertEquals("出自《部署手册》端口一节。\n\ntext-0", texts.getValue().get(0));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EsDocument>> batches = ArgumentCaptor.forClass(List.class);
        verify(elasticsearchService).bulkIndex(batches.capture());
        EsDocument first = batches.getValue().get(0);
        assertEquals("text-0", first.getTextContent(), "原文单独保存，展示和引用不带生成的说明");
        assertEquals("出自《部署手册》端口一节。", first.getChunkContext());
    }

    @Test
    void contextGenerationFailureStillIndexesChunks() {
        when(chunkContextService.isEnabled()).thenReturn(true);
        when(chunkContextService.ensureContexts("md5")).thenThrow(new IllegalStateException("llm down"));
        stubChunks(3);

        VectorizationService.VectorizationUsageResult result = service.vectorizeWithUsage("md5", "u1", "org", true, "u1");

        assertEquals(3, result.actualChunkCount());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> texts = ArgumentCaptor.forClass(List.class);
        verify(embeddingClient).embedWithProvider(texts.capture(), eq("u1"), any(), eq(PROVIDER));
        assertEquals("text-0", texts.getValue().get(0));
    }

    @Test
    void disabledContextualRetrievalIgnoresStoredContexts() {
        stubChunks(2, "旧说明");

        service.vectorizeWithUsage("md5", "u1", "org", true, "u1");

        verify(chunkContextService, never()).ensureContexts(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EsDocument>> batches = ArgumentCaptor.forClass(List.class);
        verify(elasticsearchService).bulkIndex(batches.capture());
        assertEquals(null, batches.getValue().get(0).getChunkContext());
    }

    @Test
    void noChunksIndexesNothing() {
        stubChunks(0);

        VectorizationService.VectorizationUsageResult result = service.vectorizeWithUsage("md5", "u1", "org", true, "u1");

        assertEquals(0, result.actualChunkCount());
        verify(elasticsearchService, never()).bulkIndex(anyList());
    }

    private void stubChunks(int total) {
        stubChunks(total, null);
    }

    private void stubChunks(int total, String context) {
        when(documentVectorRepository.findByFileMd5OrderByChunkIdAsc(eq("md5"), any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(1);
            int from = (int) pageable.getOffset();
            int to = Math.min(from + pageable.getPageSize(), total);
            List<DocumentVector> content = IntStream.range(Math.min(from, total), to)
                    .mapToObj(VectorizationServiceTest::chunk)
                    .peek(chunk -> chunk.setChunkContext(context))
                    .toList();
            return new SliceImpl<>(content, pageable, to < total);
        });
    }

    private static DocumentVector chunk(int chunkId) {
        DocumentVector vector = new DocumentVector();
        vector.setFileMd5("md5");
        vector.setChunkId(chunkId);
        vector.setTextContent("text-" + chunkId);
        return vector;
    }
}
