package com.yizhaoqi.smartpai.service.conflict;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.MgetRequest;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.get.GetResult;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.yizhaoqi.smartpai.config.DocumentConflictProperties;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConflictCandidateFinderTest {

    private static final String NEW_MD5 = "new-md5";
    private static final String PORT_8080 = "订单服务的 HTTP 端口配置为 8080，连接超时时间为 30 秒。";
    private static final String PORT_9090 = "订单服务的 HTTP 端口配置为 9090，连接超时时间为 30 秒。";

    private final ElasticsearchClient esClient = mock(ElasticsearchClient.class);
    private final DocumentVectorRepository vectorRepository = mock(DocumentVectorRepository.class);
    private final DocumentConflictProperties properties = new DocumentConflictProperties();
    private final List<SearchRequest> knnRequests = new ArrayList<>();
    private ConflictCandidateFinder finder;

    @BeforeEach
    void setUp() {
        finder = new ConflictCandidateFinder(esClient, vectorRepository, properties);
    }

    @Test
    void rejectsNeighborBelowSimilarityThreshold() {
        properties.setSimilarityThreshold(0.9);
        Optional<ConflictCandidate> candidate = finder.evaluate(doc(NEW_MD5, 1, PORT_8080), doc("old", 2, PORT_9090), 0.89);
        assertTrue(candidate.isEmpty());
    }

    @Test
    void rejectsNeighborFromSameFile() {
        Optional<ConflictCandidate> candidate = finder.evaluate(doc(NEW_MD5, 1, PORT_8080), doc(NEW_MD5, 2, PORT_9090), 0.99);
        assertTrue(candidate.isEmpty());
    }

    @Test
    void rejectsIdenticalTextEvenWithHighSimilarity() {
        Optional<ConflictCandidate> candidate = finder.evaluate(doc(NEW_MD5, 1, PORT_8080), doc("old", 2, PORT_8080 + " "), 0.99);
        assertTrue(candidate.isEmpty());
    }

    @Test
    void acceptsSimilarChunkWithDifferentKeyValue() {
        ConflictCandidate candidate = finder.evaluate(doc(NEW_MD5, 1, PORT_8080), doc("old", 2, PORT_9090), 0.97).orElseThrow();

        assertEquals(NEW_MD5, candidate.newChunk().fileMd5());
        assertEquals("old", candidate.existingChunk().fileMd5());
        assertEquals(2, candidate.existingChunk().chunkId());
        assertEquals(0.97, candidate.similarity(), 1e-9);
        assertTrue(candidate.newOnlyValues().contains("number:8080"));
        assertTrue(candidate.existingOnlyValues().contains("number:9090"));
    }

    @Test
    void scopeFilterReusesPermissionFilterAndExcludesNewFile() {
        Query scope = ConflictCandidateFinder.buildScopeFilter(NEW_MD5, "7", "dept-a");

        Query visibility = scope.bool().filter().get(0);
        var should = visibility.bool().should();
        assertEquals("userId", should.get(0).term().field());
        assertEquals("7", should.get(0).term().value().stringValue());
        assertEquals("public", should.get(1).term().field());
        assertEquals("orgTag", should.get(2).terms().field());
        assertEquals("dept-a", should.get(2).terms().terms().value().get(0).stringValue());
        assertEquals("fileMd5", scope.bool().mustNot().get(0).term().field());
        assertEquals(NEW_MD5, scope.bool().mustNot().get(0).term().value().stringValue());
    }

    @Test
    void scopeFilterWithoutOrgTagOnlyKeepsOwnerAndPublic() {
        Query scope = ConflictCandidateFinder.buildScopeFilter(NEW_MD5, "7", null);
        assertEquals(2, scope.bool().filter().get(0).bool().should().size());
    }

    @Test
    void cosineIsRecoveredFromEsScore() {
        assertEquals(0.95, ConflictCandidateFinder.cosineFromScore(0.975), 1e-9);
        assertEquals(0.0, ConflictCandidateFinder.cosineFromScore(null), 1e-9);
    }

    @Test
    void findCandidatesDeduplicatesPairsAndCapsPerFile() throws Exception {
        properties.setMaxConflictsPerFile(2);
        properties.setNeighborsPerChunk(3);
        stubNewChunks(doc(NEW_MD5, 1, PORT_8080), doc(NEW_MD5, 2, PORT_8080), doc(NEW_MD5, 3, PORT_8080));
        when(esClient.search(any(SearchRequest.class), eq(EsDocument.class))).thenAnswer(invocation -> {
            knnRequests.add(invocation.getArgument(0));
            return response(List.of(
                    hit(doc("old-a", 5, PORT_9090), 0.99),
                    hit(doc("old-a", 5, PORT_9090), 0.99),
                    hit(doc("old-b", 1, "年假天数按工龄计算，满 10 年可休 15 天。"), 0.99)));
        });

        List<ConflictCandidate> candidates = finder.findCandidates(NEW_MD5);

        // 同一分块对只记一次；达到单文件上限后不再扫描第 3 个分块
        assertEquals(2, candidates.size());
        assertEquals(1, candidates.get(0).newChunk().chunkId());
        assertEquals(2, candidates.get(1).newChunk().chunkId());
        assertEquals("old-a", candidates.get(1).existingChunk().fileMd5());
        assertEquals(2, knnRequests.size());
        SearchRequest request = knnRequests.get(0);
        assertEquals(3L, request.knn().get(0).k());
        assertEquals(NEW_MD5, request.knn().get(0).filter().get(0).bool().mustNot().get(0).term().value().stringValue());
    }

    @Test
    void findCandidatesReturnsEmptyWhenFileHasNoChunks() throws Exception {
        when(vectorRepository.findByFileMd5OrderByChunkIdAsc(eq(NEW_MD5), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of()));

        assertTrue(finder.findCandidates(NEW_MD5).isEmpty());
    }

    private void stubNewChunks(EsDocument... docs) throws Exception {
        List<DocumentVector> vectors = new ArrayList<>();
        List<MultiGetResponseItem<EsDocument>> items = new ArrayList<>();
        for (EsDocument doc : docs) {
            DocumentVector vector = new DocumentVector();
            vector.setFileMd5(doc.getFileMd5());
            vector.setChunkId(doc.getChunkId());
            vectors.add(vector);
            GetResult<EsDocument> result = new GetResult.Builder<EsDocument>()
                    .index("knowledge_base_v1").id(doc.getId()).found(true).source(doc).build();
            items.add(new MultiGetResponseItem.Builder<EsDocument>().result(result).build());
        }
        when(vectorRepository.findByFileMd5OrderByChunkIdAsc(eq(NEW_MD5), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(vectors));
        when(esClient.mget(any(MgetRequest.class), eq(EsDocument.class)))
                .thenReturn(new MgetResponse.Builder<EsDocument>().docs(items).build());
    }

    private static EsDocument doc(String md5, int chunkId, String text) {
        return new EsDocument(md5 + "_" + chunkId, md5, chunkId, text, null, null,
                new float[]{0.1f, 0.2f}, "v1", "7", "dept-a", false);
    }

    private static Hit<EsDocument> hit(EsDocument doc, double cosine) {
        return Hit.of(h -> h.index("knowledge_base_v1").id(doc.getId()).score((1 + cosine) / 2).source(doc));
    }

    private static SearchResponse<EsDocument> response(List<Hit<EsDocument>> hits) {
        return SearchResponse.of(r -> r
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(hits).total(t -> t.value(hits.size()).relation(TotalHitsRelation.Eq)).maxScore(1.0)));
    }
}
