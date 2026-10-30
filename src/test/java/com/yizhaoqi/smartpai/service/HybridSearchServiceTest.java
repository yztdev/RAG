package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.client.RerankClient;
import com.yizhaoqi.smartpai.config.ContextualRetrievalProperties;
import com.yizhaoqi.smartpai.config.HybridSearchProperties;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.utils.RequestDeadline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridSearchServiceTest {

    private final ElasticsearchClient esClient = mock(ElasticsearchClient.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final RerankClient rerankClient = mock(RerankClient.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final OrgTagCacheService orgTagCacheService = mock(OrgTagCacheService.class);
    private final FileUploadRepository fileUploadRepository = mock(FileUploadRepository.class);
    private final HybridSearchProperties properties = new HybridSearchProperties();
    private final ContextualRetrievalProperties contextualProperties = new ContextualRetrievalProperties();
    private final List<SearchRequest> capturedRequests = Collections.synchronizedList(new ArrayList<>());

    private HybridSearchService service;
    private List<Hit<EsDocument>> bm25Hits;
    private List<Hit<EsDocument>> knnHits;
    private RuntimeException knnFailure;
    private RuntimeException bm25Failure;

    @BeforeEach
    void setUp() throws Exception {
        service = new HybridSearchService(esClient, embeddingClient, rerankClient, properties, contextualProperties,
                userRepository, orgTagCacheService, fileUploadRepository, Runnable::run);

        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("alice")).thenReturn(List.of("dept-a", "company"));
        when(embeddingClient.embed(anyList(), anyString(), eq(EmbeddingClient.UsageType.QUERY)))
                .thenReturn(List.of(new float[]{0.1f, 0.2f}));
        FileUpload upload = new FileUpload();
        upload.setFileMd5("md5");
        upload.setFileName("manual.pdf");
        when(fileUploadRepository.findByFileMd5In(anyList())).thenReturn(List.of(upload));

        // 文档 kw 只有关键词命中（BM25 第 1），vec 只有语义相近（KNN 第 2），both 两路都召回
        bm25Hits = List.of(hit("kw", 1), hit("both", 2));
        knnHits = List.of(hit("both", 2), hit("vec", 3));
        when(esClient.search(any(SearchRequest.class), eq(EsDocument.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            capturedRequests.add(request);
            if (request.knn().isEmpty()) {
                // 纯文本降级请求带 min_score，BM25 召回请求不带，借此区分两者
                if (bm25Failure != null && request.minScore() == null) {
                    throw bm25Failure;
                }
                return response(bm25Hits);
            }
            if (knnFailure != null) {
                throw knnFailure;
            }
            return response(knnHits);
        });
    }

    @Test
    void fusesBothPathsThenTruncatesByRerankThreshold() {
        // RRF 顺序：both(1/62+1/61) > kw(1/61) > vec(1/62)
        when(rerankClient.rerank(eq("E1024 错误码"), anyList())).thenReturn(List.of(
                new RerankClient.RerankHit(1, 0.91),
                new RerankClient.RerankHit(0, 0.55),
                new RerankClient.RerankHit(2, 0.05)
        ));

        List<SearchResult> results = service.searchWithPermission("E1024 错误码", "7", 5);

        assertEquals(List.of(1, 2), results.stream().map(SearchResult::getChunkId).toList(),
                "关键词独有的 kw 被保留，低于阈值的 vec 被丢弃，topK 只是上限");
        assertEquals(0.91, results.get(0).getScore(), 1e-9);
        assertEquals("HYBRID", results.get(0).getRetrievalMode());
        assertEquals("manual.pdf", results.get(0).getFileName());
        verify(rerankClient).rerank("E1024 错误码", List.of("text-both", "text-kw", "text-vec"));
    }

    @Test
    void topKCapsResultsEvenWhenAllPassThreshold() {
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(List.of(
                new RerankClient.RerankHit(0, 0.9),
                new RerankClient.RerankHit(1, 0.8),
                new RerankClient.RerankHit(2, 0.7)
        ));

        assertEquals(2, service.searchWithPermission("q", "7", 2).size());
    }

    @Test
    void rerankFailureFallsBackToRrfOrder() {
        when(rerankClient.rerank(anyString(), anyList())).thenThrow(new IllegalStateException("rerank down"));
        // 这里只验证 RRF 排序，阈值过滤另有用例
        properties.setRrfScoreThreshold(0);

        List<SearchResult> results = service.searchWithPermission("q", "7", 2);

        assertEquals(List.of(2, 1), results.stream().map(SearchResult::getChunkId).toList());
        double raw = 1.0 / 62 + 1.0 / 61;
        assertEquals(raw, results.get(0).getRawScore(), 1e-12, "原始 RRF 分保留在 rawScore");
        assertEquals(raw / (2.0 / 61), results.get(0).getScore(), 1e-12, "出口分数按 RRF 理论上界 2/(k+1) 换算");
        assertTrue(results.stream().allMatch(r -> "HYBRID_RRF".equals(r.getRetrievalMode())),
                "RRF 分数与 rerank 分数量纲不同，单独标记检索模式");
    }

    @Test
    void rrfFallbackDropsLowScoringSinglePathResults() {
        when(rerankClient.rerank(anyString(), anyList())).thenThrow(new IllegalStateException("rerank down"));
        // kw 只被 BM25 排第 1（1/61≈0.0164），vec 只被 KNN 排第 2（1/62≈0.0161），both 两路都有
        properties.setRrfScoreThreshold(0.0163);

        HybridSearchService.SearchTrace trace = service.searchWithTrace("q", "7", 5);

        assertEquals(List.of(2, 1), trace.results().stream().map(SearchResult::getChunkId).toList(),
                "只被单路召回且分数低于阈值的 vec 不交给模型");
        assertEquals(1, trace.thresholdDroppedInTopK());
        assertEquals(3, trace.candidates().size(), "候选列表保留全部 RRF 结果，供评测分析");
    }

    @Test
    void rrfThresholdIsHalvedWhenOnlyOnePathRecalled() {
        knnFailure = new RuntimeException("knn failed");
        when(rerankClient.rerank(anyString(), anyList())).thenThrow(new IllegalStateException("rerank down"));
        // 两路量纲的 0.028 在单路时等于 0.014：BM25 第 1、2 名（1/61、1/62）都保留
        properties.setRrfScoreThreshold(0.028);

        List<SearchResult> results = service.searchWithPermission("q", "7", 5);

        assertEquals(List.of(1, 2), results.stream().map(SearchResult::getChunkId).toList());
        assertTrue(results.stream().allMatch(r -> "BM25_ONLY_RRF".equals(r.getRetrievalMode())));
    }

    @Test
    void scoresAreNormalizedPerModeWithFixedUpperBounds() {
        assertEquals(0.37, service.normalizeScore("HYBRID", 0.37), 1e-12, "rerank 分原样");
        assertEquals(1.0, service.normalizeScore("HYBRID_RRF", 2.0 / 61), 1e-12, "两路都排第一换算为 1");
        assertEquals(0.5, service.normalizeScore("BM25_ONLY_RRF", 1.0 / 61), 1e-12, "单路第一名换算为 0.5");
        assertEquals(0.5, service.normalizeScore("TEXT_ONLY", 10.0), 1e-12, "BM25 按 score/(score+10) 饱和换算");
        assertTrue(service.normalizeScore("TEXT_ONLY", 1000.0) < 1.0);
    }

    @Test
    void rerankIsSkippedWhenRequestDeadlineIsAlmostUsedUp() {
        // 这里只验证 RRF 排序，阈值过滤另有用例
        properties.setRrfScoreThreshold(0);
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(List.of(new RerankClient.RerankHit(0, 0.99)));

        List<SearchResult> results;
        try (RequestDeadline.Scope ignored = RequestDeadline.open(RequestDeadline.after(Duration.ofMillis(1500)))) {
            results = service.searchWithPermission("q", "7", 2);
        }

        verify(rerankClient, never()).rerank(anyString(), anyList());
        assertEquals(List.of(2, 1), results.stream().map(SearchResult::getChunkId).toList());
        assertTrue(results.stream().allMatch(r -> "HYBRID_RRF".equals(r.getRetrievalMode())));
    }

    @Test
    void rerankDisabledUsesRrfTopK() {
        properties.getRerank().setEnabled(false);
        // 这里只验证 RRF 排序，阈值过滤另有用例
        properties.setRrfScoreThreshold(0);

        List<SearchResult> results = service.searchWithPermission("q", "7", 3);

        assertEquals(List.of(2, 1, 3), results.stream().map(SearchResult::getChunkId).toList());
        assertEquals("HYBRID_RRF", results.get(0).getRetrievalMode());
        verify(rerankClient, never()).rerank(anyString(), anyList());
    }

    @Test
    void bothPathsAreIndependentAndCarryPermissionFilter() {
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(List.of());

        service.searchWithPermission("q", "7", 5);

        assertEquals(2, capturedRequests.size());
        SearchRequest bm25 = capturedRequests.stream().filter(r -> r.knn().isEmpty()).findFirst().orElseThrow();
        SearchRequest knn = capturedRequests.stream().filter(r -> !r.knn().isEmpty()).findFirst().orElseThrow();

        assertEquals(150, bm25.size(), "每路召回窗口 = topK * 30");
        assertEquals(150, knn.size());
        assertEquals(150L, knn.knn().get(0).k());
        assertTrue(knn.query() == null, "KNN 路不再要求必须命中关键词");
        assertTrue(knn.rescore().isEmpty(), "不再使用 rescore");
        assertPermissionFilter(bm25.query().bool().filter().get(0));
        assertPermissionFilter(knn.knn().get(0).filter().get(0));
    }

    @Test
    void embeddingFailureKeepsBm25PathThroughRrfAndRerank() {
        when(embeddingClient.embed(anyList(), anyString(), eq(EmbeddingClient.UsageType.QUERY)))
                .thenThrow(new RuntimeException("embedding down"));
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(List.of(
                new RerankClient.RerankHit(1, 0.8), new RerankClient.RerankHit(0, 0.6)));

        List<SearchResult> results = service.searchWithPermission("q", "7", 5);

        assertEquals(1, capturedRequests.size(), "向量生成失败时只发 BM25 一路");
        SearchRequest request = capturedRequests.get(0);
        assertTrue(request.knn().isEmpty());
        assertTrue(request.minScore() == null, "不是纯文本降级");
        assertPermissionFilter(request.query().bool().filter().get(0));
        assertEquals(List.of(2, 1), results.stream().map(SearchResult::getChunkId).toList());
        assertTrue(results.stream().allMatch(r -> "BM25_ONLY".equals(r.getRetrievalMode())));
    }

    @Test
    void knnFailureKeepsBm25ResultsInsteadOfDroppingToTextOnly() {
        knnFailure = new RuntimeException("knn failed");
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(List.of(
                new RerankClient.RerankHit(0, 0.9), new RerankClient.RerankHit(1, 0.4)));

        List<SearchResult> results = service.searchWithPermission("q", "7", 5);

        assertEquals(List.of(1, 2), results.stream().map(SearchResult::getChunkId).toList());
        assertTrue(results.stream().allMatch(r -> "BM25_ONLY".equals(r.getRetrievalMode())));
        verify(rerankClient).rerank(eq("q"), anyList());
    }

    @Test
    void bm25FailureKeepsKnnResults() {
        bm25Failure = new RuntimeException("bm25 failed");
        when(rerankClient.rerank(anyString(), anyList())).thenThrow(new IllegalStateException("rerank down"));

        List<SearchResult> results = service.searchWithPermission("q", "7", 5);

        assertEquals(List.of(2, 3), results.stream().map(SearchResult::getChunkId).toList());
        assertTrue(results.stream().allMatch(r -> "KNN_ONLY_RRF".equals(r.getRetrievalMode())));
    }

    @Test
    void bothPathsFailingFallsBackToTextOnly() {
        bm25Failure = new RuntimeException("bm25 failed");
        knnFailure = new RuntimeException("knn failed");

        List<SearchResult> results = service.searchWithPermission("q", "7", 5);

        SearchRequest textOnly = capturedRequests.get(capturedRequests.size() - 1);
        assertEquals(0.3, textOnly.minScore(), 1e-9);
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(r -> "TEXT_ONLY".equals(r.getRetrievalMode())));
    }

    @Test
    void bm25MatchesOnlyTextContentWhenContextualRetrievalDisabled() {
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(List.of());

        service.searchWithPermission("q", "7", 5);

        SearchRequest bm25 = capturedRequests.stream().filter(r -> r.knn().isEmpty()).findFirst().orElseThrow();
        Query keyword = contentQuery(bm25);
        assertTrue(keyword.isMatch());
        assertEquals("textContent", keyword.match().field());
    }

    @Test
    void fileNameIsAnExtraBoostOnlyForChunksMatchingContent() {
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(List.of());

        service.searchWithPermission("向量检索设计", "7", 5);

        SearchRequest bm25 = capturedRequests.stream().filter(r -> r.knn().isEmpty()).findFirst().orElseThrow();
        Query keyword = bm25.query().bool().must().get(0);
        assertTrue(keyword.isBool());
        assertEquals("textContent", keyword.bool().must().get(0).match().field(), "正文必须匹配");
        Query fileName = keyword.bool().should().get(0);
        assertEquals("fileName", fileName.match().field(), "文件名单独成字段，只作加分项");
        assertEquals(0.5f, fileName.match().boost(), 1e-6);
        assertEquals(1, keyword.bool().should().size(), "短语加分默认关闭");
    }

    @Test
    void minimumShouldMatchAndPhraseBoostAreConfigurable() {
        properties.getKeyword().setFileNameBoost(0);
        properties.getKeyword().setMinimumShouldMatch("30%");
        properties.getKeyword().setPhraseBoost(1.5f);

        Query keyword = service.keywordQuery("RAG知识库的混合检索用了哪两路召回");

        assertEquals("30%", keyword.bool().must().get(0).match().minimumShouldMatch());
        assertTrue(keyword.bool().should().get(0).isMatchPhrase());
        assertEquals(1.5f, keyword.bool().should().get(0).matchPhrase().boost(), 1e-6);

        properties.getKeyword().setPhraseBoost(0);
        assertTrue(service.keywordQuery("q").isMatch(), "加分项都关闭时就是单字段 match");
    }

    private Query contentQuery(SearchRequest bm25) {
        Query keyword = bm25.query().bool().must().get(0);
        return keyword.isBool() ? keyword.bool().must().get(0) : keyword;
    }

    @Test
    void contextualRetrievalScoresContextPlusChunkButReturnsOriginalText() {
        contextualProperties.setEnabled(true);
        EsDocument withContext = new EsDocument("ctx", "md5", 9, "营收比上季度增长 3%", "出自 ACME 2023 年二季度财报的营收部分。",
                null, null, null, "v1", "7", "dept-a", false);
        bm25Hits = List.of(Hit.of(h -> h.index("knowledge_base_v1").id("ctx").score(1.0).source(withContext)));
        knnHits = List.of();
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(List.of(new RerankClient.RerankHit(0, 0.9)));

        List<SearchResult> results = service.searchWithPermission("ACME 二季度营收", "7", 5);

        SearchRequest bm25 = capturedRequests.stream().filter(r -> r.knn().isEmpty()).findFirst().orElseThrow();
        Query keyword = contentQuery(bm25);
        assertTrue(keyword.isCombinedFields(), "说明和原文当成一个字段算 BM25");
        assertEquals(List.of("chunkContext", "textContent"), keyword.combinedFields().fields());
        verify(rerankClient).rerank("ACME 二季度营收", List.of("出自 ACME 2023 年二季度财报的营收部分。\n\n营收比上季度增长 3%"));
        assertEquals("营收比上季度增长 3%", results.get(0).getTextContent(), "展示和引用只用原文");
        assertEquals("营收比上季度增长 3%", results.get(0).getMatchedChunkText());
    }

    @Test
    void recallWindowIsBoundedByConfig() {
        assertEquals(150, service.recallWindow(5));
        assertEquals(300, service.recallWindow(20));
        properties.setMaxRecallWindow(10);
        assertEquals(20, service.recallWindow(20), "窗口至少不小于 topK");
    }

    private static void assertPermissionFilter(Query filter) {
        var should = filter.bool().should();
        assertEquals(3, should.size());
        assertEquals("userId", should.get(0).term().field());
        assertEquals("7", should.get(0).term().value().stringValue());
        assertEquals("public", should.get(1).term().field());
        assertEquals("orgTag", should.get(2).terms().field());
        assertEquals(2, should.get(2).terms().terms().value().size());
        assertEquals("1", filter.bool().minimumShouldMatch());
    }

    private static Hit<EsDocument> hit(String id, int chunkId) {
        EsDocument doc = new EsDocument(id, "md5", chunkId, "text-" + id, null, null, null, "v1", "7", "dept-a", false);
        return Hit.of(h -> h.index("knowledge_base_v1").id(id).score(1.0).source(doc));
    }

    private static SearchResponse<EsDocument> response(List<Hit<EsDocument>> hits) {
        return SearchResponse.of(r -> r
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(hits).total(t -> t.value(hits.size()).relation(TotalHitsRelation.Eq)).maxScore(1.0)));
    }
}
