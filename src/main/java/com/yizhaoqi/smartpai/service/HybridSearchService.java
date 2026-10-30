package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.client.RerankClient;
import com.yizhaoqi.smartpai.config.ContextualRetrievalProperties;
import com.yizhaoqi.smartpai.config.HybridSearchProperties;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.service.chunk.ContextualChunkText;
import com.yizhaoqi.smartpai.service.index.KnowledgeIndexNames;
import com.yizhaoqi.smartpai.service.search.RrfFusion;
import com.yizhaoqi.smartpai.utils.RequestDeadline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 混合搜索服务：BM25 与 KNN 两路独立并行召回 → RRF 融合 → rerank 精排 → 按分数阈值截断（topK 只是上限）。
 * 两路召回都带权限过滤，确保用户只能搜到自己的、公开的、所属组织（含层级继承）的文档。
 * <p>
 * 降级顺序：一路召回失败时用另一路继续走 RRF 和 rerank（模式标为 BM25_ONLY / KNN_ONLY）；rerank 跳过或失败时按 RRF 排序并用
 * RRF 阈值过滤（模式加 _RRF 后缀）；两路都失败才退到纯文本检索（TEXT_ONLY）。
 * 出口分数统一换算到 0 到 1（rerank 分原样、RRF 分除以理论上界、BM25 分做饱和换算），原始分保留在 rawScore。
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);
    public static final String MODE_HYBRID = "HYBRID";
    public static final String MODE_BM25_ONLY = "BM25_ONLY";
    public static final String MODE_KNN_ONLY = "KNN_ONLY";
    // rerank 跳过或失败时按 RRF 排序兜底，在召回模式后加该后缀（如 HYBRID_RRF），分数按 RRF 量纲换算
    public static final String RRF_SUFFIX = "_RRF";
    public static final String MODE_HYBRID_RRF = MODE_HYBRID + RRF_SUFFIX;
    public static final String MODE_TEXT_ONLY = "TEXT_ONLY";
    // 评测分阶段统计时，每一路召回保留的条数
    private static final int TRACE_PATH_LIMIT = 50;
    private static final int ES_MAX_NUM_CANDIDATES = 10000;
    private static final double TEXT_ONLY_MIN_SCORE = 0.3d;
    static final String FIELD_TEXT = "textContent";
    static final String FIELD_CHUNK_CONTEXT = "chunkContext";
    static final String FIELD_FILE_NAME = "fileName";

    private final ElasticsearchClient esClient;
    private final EmbeddingClient embeddingClient;
    private final RerankClient rerankClient;
    private final HybridSearchProperties properties;
    private final ContextualRetrievalProperties contextualProperties;
    private final UserRepository userRepository;
    private final OrgTagCacheService orgTagCacheService;
    private final FileUploadRepository fileUploadRepository;
    private final Executor recallExecutor;

    public HybridSearchService(ElasticsearchClient esClient,
                               EmbeddingClient embeddingClient,
                               RerankClient rerankClient,
                               HybridSearchProperties properties,
                               ContextualRetrievalProperties contextualProperties,
                               UserRepository userRepository,
                               OrgTagCacheService orgTagCacheService,
                               FileUploadRepository fileUploadRepository,
                               @Qualifier("searchRecallExecutor") Executor recallExecutor) {
        this.esClient = esClient;
        this.embeddingClient = embeddingClient;
        this.rerankClient = rerankClient;
        this.properties = properties;
        this.contextualProperties = contextualProperties;
        this.userRepository = userRepository;
        this.orgTagCacheService = orgTagCacheService;
        this.fileUploadRepository = fileUploadRepository;
        this.recallExecutor = recallExecutor;
    }

    /**
     * 带权限过滤的混合搜索（自己的文档、公开文档、所属组织及其上级组织的文档）
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        return search(query, userId, topK, false).results();
    }

    /**
     * 与 {@link #searchWithPermission} 相同的检索，额外返回各阶段的中间结果（每一路召回、RRF 候选），供评测分阶段统计
     */
    public SearchTrace searchWithTrace(String query, String userId, int topK) {
        return search(query, userId, topK, true);
    }

    private SearchTrace search(String query, String userId, int topK, boolean detailed) {
        logger.debug("开始带权限搜索，查询: {}, 用户ID: {}", query, userId);
        Query permissionFilter;
        try {
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            String userDbId = getUserDbId(userId);
            logger.debug("用户 {} 的数据库ID: {}, 有效组织标签: {}", userId, userDbId, userEffectiveTags);
            permissionFilter = buildPermissionFilter(userDbId, userEffectiveTags);
        } catch (Exception e) {
            logger.error("解析用户权限失败，拒绝搜索: userId={}", userId, e);
            return SearchTrace.empty();
        }

        try {
            return hybridSearch(query, userId, topK, permissionFilter, detailed);
        } catch (Exception e) {
            logger.error("带权限的混合搜索失败，降级为纯文本搜索", e);
            return SearchTrace.textOnly(safeTextOnlySearch(query, permissionFilter, topK, TEXT_ONLY_MIN_SCORE));
        }
    }

    /**
     * 原始搜索方法，不包含权限过滤，保留向后兼容性
     */
    public List<SearchResult> search(String query, int topK) {
        logger.warn("使用了没有权限过滤的搜索方法，建议使用 searchWithPermission 方法");
        try {
            return hybridSearch(query, "system", topK, null, false).results();
        } catch (Exception e) {
            logger.error("混合搜索失败，降级为纯文本搜索", e);
            try {
                return textOnlySearch(query, null, topK, null);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                throw new RuntimeException("搜索完全失败", fallbackError);
            }
        }
    }

    private SearchTrace hybridSearch(String query, String requesterId, int topK, Query filter, boolean detailed) {
        // 向量生成失败等同于 KNN 这一路失败，BM25 照常召回
        List<Float> queryVector = embedToVectorList(query, requesterId);
        int window = recallWindow(topK);
        CompletableFuture<List<Hit<EsDocument>>> bm25Future =
                CompletableFuture.supplyAsync(() -> recall(buildBm25Request(query, filter, window)), recallExecutor);
        CompletableFuture<List<Hit<EsDocument>>> knnFuture = queryVector == null ? null
                : CompletableFuture.supplyAsync(() -> recall(buildKnnRequest(queryVector, filter, window)), recallExecutor);

        // 两路共用一个等待截止时间：取召回超时配置和问答请求剩余时间中较小的值
        long deadlineNanos = System.nanoTime()
                + RequestDeadline.cap(Duration.ofSeconds(properties.getRecallTimeoutSeconds())).toNanos();
        RecallOutcome bm25 = await("BM25", bm25Future, deadlineNanos);
        RecallOutcome knn = knnFuture == null ? RecallOutcome.failed("查询向量生成失败") : await("KNN", knnFuture, deadlineNanos);
        if (!bm25.ok() && !knn.ok()) {
            throw new IllegalStateException("双路召回均失败: bm25=" + bm25.error() + ", knn=" + knn.error());
        }
        String recallMode = bm25.ok() && knn.ok() ? MODE_HYBRID : bm25.ok() ? MODE_BM25_ONLY : MODE_KNN_ONLY;
        if (!MODE_HYBRID.equals(recallMode)) {
            logger.warn("单路召回，用成功的一路继续 RRF 和 rerank: mode={}, bm25Error={}, knnError={}",
                    recallMode, bm25.error(), knn.error());
        }

        List<RrfFusion.Fused<Hit<EsDocument>>> fused =
                RrfFusion.fuse(properties.getRrfK(), Hit::id, bm25.hits(), knn.hits());
        logger.debug("召回完成: mode={}, window={}, bm25={}, knn={}, fused={}",
                recallMode, window, bm25.hits().size(), knn.hits().size(), fused.size());

        Ranked ranked = rerankAndTruncate(query, fused, topK, recallMode);
        attachFileNames(ranked.results());
        if (!detailed) {
            return new SearchTrace(ranked.results(), List.of(), List.of(), List.of(), ranked.mode(), ranked.thresholdDroppedInTopK());
        }
        List<SearchResult> candidates = candidates(fused, topK).stream()
                .map(c -> toSearchResult(c.item().source(), c.score(), recallMode + RRF_SUFFIX))
                .collect(Collectors.toCollection(ArrayList::new));
        List<SearchResult> bm25Ranked = pathResults(bm25.hits(), "BM25");
        List<SearchResult> knnRanked = pathResults(knn.hits(), "KNN");
        List<SearchResult> all = new ArrayList<>(candidates);
        all.addAll(bm25Ranked);
        all.addAll(knnRanked);
        attachFileNames(all);
        return new SearchTrace(ranked.results(), candidates, bm25Ranked, knnRanked, ranked.mode(), ranked.thresholdDroppedInTopK());
    }

    /**
     * 等待单路召回；超时或异常都只让这一路失败，不影响另一路
     */
    private RecallOutcome await(String path, CompletableFuture<List<Hit<EsDocument>>> future, long deadlineNanos) {
        try {
            return RecallOutcome.ok(future.get(Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            future.cancel(true);
            return RecallOutcome.failed(path + " 召回超时");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return RecallOutcome.failed(path + " 召回失败: " + cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RecallOutcome.failed(path + " 召回被中断");
        }
    }

    private List<SearchResult> pathResults(List<Hit<EsDocument>> hits, String path) {
        return hits.stream()
                .limit(TRACE_PATH_LIMIT)
                .map(hit -> {
                    SearchResult result = toSearchResult(hit.source(), hit.score(), path);
                    // 单路召回的原始分（BM25 分 / 向量相似度）只用于评测排查，不做换算
                    result.setScore(hit.score());
                    return result;
                })
                .collect(Collectors.toCollection(ArrayList::new));
    }

    int recallWindow(int topK) {
        long window = (long) Math.max(topK, 1) * Math.max(properties.getRecallMultiplier(), 1);
        return (int) Math.max(topK, Math.min(window, properties.getMaxRecallWindow()));
    }

    private List<RrfFusion.Fused<Hit<EsDocument>>> candidates(List<RrfFusion.Fused<Hit<EsDocument>>> fused, int topK) {
        return fused.subList(0, Math.min(fused.size(), Math.max(properties.getRerank().getMaxCandidates(), topK)));
    }

    private Ranked rerankAndTruncate(String query, List<RrfFusion.Fused<Hit<EsDocument>>> fused, int topK, String recallMode) {
        HybridSearchProperties.Rerank cfg = properties.getRerank();
        List<RrfFusion.Fused<Hit<EsDocument>>> candidates = candidates(fused, topK);
        if (candidates.isEmpty()) {
            return new Ranked(new ArrayList<>(), recallMode, 0);
        }
        if (!cfg.isEnabled()) {
            return rrfTopK(candidates, topK, recallMode);
        }
        if (!RequestDeadline.hasAtLeast(Duration.ofMillis(cfg.getSkipBelowMillis()))) {
            logger.info("问答请求剩余时间不足 {}ms，跳过 rerank，按 RRF 顺序返回", cfg.getSkipBelowMillis());
            return rrfTopK(candidates, topK, recallMode);
        }

        try {
            // 上下文检索开启时 rerank 也看"说明 + 原文"，与 embedding / BM25 的索引文本一致
            List<String> documents = candidates.stream()
                    .map(c -> truncate(rerankText(c.item().source()), cfg.getMaxDocumentChars()))
                    .toList();
            List<RerankClient.RerankHit> ranked = rerankClient.rerank(query, documents);
            List<SearchResult> results = ranked.stream()
                    .filter(hit -> hit.score() >= cfg.getScoreThreshold())
                    .limit(topK)
                    .map(hit -> toSearchResult(candidates.get(hit.index()).item().source(), hit.score(), recallMode))
                    .collect(Collectors.toCollection(ArrayList::new));
            // 排在前 topK 名、却因为分数低于阈值被丢掉的条数：为 0 说明阈值只过滤了 topK 之外的结果
            int dropped = (int) ranked.stream().limit(topK).filter(hit -> hit.score() < cfg.getScoreThreshold()).count();
            logger.debug("rerank 截断: candidates={}, 阈值={}, 前 topK 中被阈值丢掉={}, 返回={}",
                    candidates.size(), cfg.getScoreThreshold(), dropped, results.size());
            return new Ranked(results, recallMode, dropped);
        } catch (Exception e) {
            logger.warn("rerank 调用失败，按 RRF 排名截取 topK: {}", e.getMessage());
            return rrfTopK(candidates, topK, recallMode);
        }
    }

    /**
     * RRF 兜底同样按阈值过滤：只被单路召回且名次靠后的结果不交给模型，宁可返回空也不塞不相关的文档
     */
    private Ranked rrfTopK(List<RrfFusion.Fused<Hit<EsDocument>>> candidates, int topK, String recallMode) {
        // 阈值按两路都成功时的量纲配置；只有一路召回时分数上限减半，阈值也减半，保持"平均名次"的含义一致
        int activePaths = MODE_HYBRID.equals(recallMode) ? 2 : 1;
        double threshold = properties.getRrfScoreThreshold() * activePaths / 2d;
        String mode = recallMode + RRF_SUFFIX;
        List<SearchResult> results = candidates.stream()
                .filter(c -> c.score() >= threshold)
                .limit(topK)
                .map(c -> toSearchResult(c.item().source(), c.score(), mode))
                .collect(Collectors.toCollection(ArrayList::new));
        int dropped = (int) candidates.stream().limit(topK).filter(c -> c.score() < threshold).count();
        return new Ranked(results, mode, dropped);
    }

    /**
     * 出口分数换算到 0 到 1，用各自的理论上界做固定换算（不用 min-max：分母随当批结果变化，跨查询不可比）：
     * rerank 分本身在 0 到 1；RRF 分除以两路都排第一时的上界 2/(k+1)；BM25 分无上界，按 score/(score+饱和常数) 换算
     */
    double normalizeScore(String mode, double raw) {
        if (MODE_TEXT_ONLY.equals(mode)) {
            double saturation = Math.max(properties.getBm25ScoreSaturation(), 1e-9);
            return raw <= 0 ? 0d : raw / (raw + saturation);
        }
        if (mode != null && mode.endsWith(RRF_SUFFIX)) {
            double upperBound = 2d / (Math.max(properties.getRrfK(), 1) + 1);
            return Math.min(1d, Math.max(0d, raw / upperBound));
        }
        return Math.min(1d, Math.max(0d, raw));
    }

    private List<Hit<EsDocument>> recall(SearchRequest request) {
        try {
            SearchResponse<EsDocument> response = esClient.search(request, EsDocument.class);
            return response.hits().hits().stream()
                    .filter(hit -> hit.source() != null)
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("召回查询失败: " + e.getMessage(), e);
        }
    }

    SearchRequest buildBm25Request(String query, Query filter, int window) {
        return SearchRequest.of(s -> s
                .index(KnowledgeIndexNames.ALIAS)
                .source(src -> src.filter(f -> f.excludes("vector")))
                .query(q -> q.bool(b -> {
                    b.must(keywordQuery(query));
                    if (filter != null) {
                        b.filter(filter);
                    }
                    return b;
                }))
                .size(window));
    }

    /**
     * BM25 关键词查询。上下文检索开启时用 combined_fields 把 chunkContext 和 textContent 当成一个字段
     * （BM25F），效果等同于对"说明 + 原文"的拼接文本打分，而原文字段仍保持干净，只用于展示和引用；
     * 两个字段必须使用同一分词器（mapping 里都是 IK）。没有说明的旧分块只在 textContent 上匹配。
     */
    Query keywordQuery(String query) {
        HybridSearchProperties.Keyword cfg = properties.getKeyword();
        Query content = contentQuery(query, cfg.getMinimumShouldMatch());
        boolean fileNameScoring = cfg.getFileNameBoost() > 0;
        boolean phraseScoring = cfg.getPhraseBoost() > 0;
        if (!fileNameScoring && !phraseScoring) {
            return content;
        }
        // 文件名和短语都是加分项（should），只给正文已经匹配的分块加分；
        // 文件名单独成字段，不和正文合并统计词频，否则标题词会被稀释
        return Query.of(q -> q.bool(b -> {
            b.must(content);
            if (fileNameScoring) {
                b.should(sh -> sh.match(m -> m.field(FIELD_FILE_NAME).query(query).boost(cfg.getFileNameBoost())));
            }
            if (phraseScoring) {
                b.should(sh -> sh.matchPhrase(m -> m.field(FIELD_TEXT).query(query)
                        .slop(Math.max(cfg.getPhraseSlop(), 0)).boost(cfg.getPhraseBoost())));
            }
            return b;
        }));
    }

    private Query contentQuery(String query, String minimumShouldMatch) {
        boolean limitTerms = minimumShouldMatch != null && !minimumShouldMatch.isBlank();
        if (contextualProperties != null && contextualProperties.isEnabled()) {
            return Query.of(q -> q.combinedFields(c -> {
                c.query(query).fields(FIELD_CHUNK_CONTEXT, FIELD_TEXT);
                if (limitTerms) {
                    c.minimumShouldMatch(minimumShouldMatch);
                }
                return c;
            }));
        }
        return Query.of(q -> q.match(m -> {
            m.field(FIELD_TEXT).query(query);
            if (limitTerms) {
                m.minimumShouldMatch(minimumShouldMatch);
            }
            return m;
        }));
    }

    private String rerankText(EsDocument doc) {
        boolean contextual = contextualProperties != null && contextualProperties.isEnabled();
        return ContextualChunkText.compose(contextual ? doc.getChunkContext() : null, doc.getTextContent());
    }

    SearchRequest buildKnnRequest(List<Float> queryVector, Query filter, int window) {
        long numCandidates = Math.min((long) window * Math.max(properties.getKnnNumCandidatesMultiplier(), 1), ES_MAX_NUM_CANDIDATES);
        return SearchRequest.of(s -> s
                .index(KnowledgeIndexNames.ALIAS)
                .source(src -> src.filter(f -> f.excludes("vector")))
                .knn(kn -> {
                    kn.field("vector")
                            .queryVector(queryVector)
                            .k(window)
                            .numCandidates(Math.max(numCandidates, window));
                    // 过滤放在 knn 内部做预过滤，保证返回的 k 条都是有权限的文档
                    if (filter != null) {
                        kn.filter(filter);
                    }
                    return kn;
                })
                .size(window));
    }

    /**
     * 权限过滤：自己的文档 OR 公开文档 OR 组织标签命中（含层级继承后的有效标签）
     */
    public static Query buildPermissionFilter(String userDbId, List<String> userEffectiveTags) {
        return Query.of(q -> q.bool(b -> {
            b.should(s -> s.term(t -> t.field("userId").value(userDbId)));
            b.should(s -> s.term(t -> t.field("public").value(true)));
            if (userEffectiveTags != null && !userEffectiveTags.isEmpty()) {
                b.should(s -> s.terms(t -> t.field("orgTag")
                        .terms(tv -> tv.value(userEffectiveTags.stream()
                                .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                                .toList()))));
            }
            return b.minimumShouldMatch("1");
        }));
    }

    private List<SearchResult> safeTextOnlySearch(String query, Query filter, int topK, Double minScore) {
        try {
            return textOnlySearch(query, filter, topK, minScore);
        } catch (Exception e) {
            logger.error("纯文本搜索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 纯文本（BM25）搜索，embedding 失败或混合检索异常时的降级方案
     */
    private List<SearchResult> textOnlySearch(String query, Query filter, int topK, Double minScore) throws Exception {
        SearchRequest request = SearchRequest.of(s -> {
            s.index(KnowledgeIndexNames.ALIAS)
                    .source(src -> src.filter(f -> f.excludes("vector")))
                    .query(q -> q.bool(b -> {
                        b.must(keywordQuery(query));
                        if (filter != null) {
                            b.filter(filter);
                        }
                        return b;
                    }))
                    .size(topK);
            if (minScore != null) {
                s.minScore(minScore);
            }
            return s;
        });
        List<SearchResult> results = esClient.search(request, EsDocument.class).hits().hits().stream()
                .filter(hit -> hit.source() != null)
                .map(hit -> toSearchResult(hit.source(), hit.score(), MODE_TEXT_ONLY))
                .collect(Collectors.toCollection(ArrayList::new));
        logger.debug("返回纯文本搜索结果数量: {}", results.size());
        attachFileNames(results);
        return results;
    }

    private SearchResult toSearchResult(EsDocument doc, Double rawScore, String mode) {
        SearchResult result = new SearchResult(
                doc.getFileMd5(),
                doc.getChunkId(),
                doc.getTextContent(),
                rawScore == null ? null : normalizeScore(mode, rawScore),
                doc.getUserId(),
                doc.getOrgTag(),
                doc.isPublic(),
                null,
                doc.getPageNumber(),
                doc.getAnchorText(),
                mode,
                doc.getTextContent()
        );
        result.setRawScore(rawScore);
        return result;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    /**
     * 生成查询向量，返回 List<Float>，失败时返回 null
     */
    private List<Float> embedToVectorList(String text, String requesterId) {
        try {
            List<float[]> vecs = embeddingClient.embed(List.of(text), requesterId, EmbeddingClient.UsageType.QUERY);
            if (vecs == null || vecs.isEmpty()) {
                logger.warn("生成的向量为空");
                return null;
            }
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) {
                list.add(v);
            }
            return list;
        } catch (Exception e) {
            logger.error("生成向量失败", e);
            return null;
        }
    }

    /**
     * 获取用户的有效组织标签（包含层级关系）
     */
    private List<String> getUserEffectiveOrgTags(String userId) {
        try {
            User user = findUser(userId);
            return orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取用户的数据库ID用于权限过滤
     */
    private String getUserDbId(String userId) {
        try {
            return findUser(userId).getId().toString();
        } catch (Exception e) {
            logger.error("获取用户数据库ID失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }

    private User findUser(String userId) {
        try {
            Long userIdLong = Long.parseLong(userId);
            return userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
        } catch (NumberFormatException e) {
            // 如果userId不是数字格式，则假设它就是username
            return userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
        }
    }

    private void attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        try {
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .collect(Collectors.toSet());
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new ArrayList<>(md5Set));
            Map<String, String> md5ToName = uploads.stream()
                    .collect(Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName, (existing, replacement) -> existing));
            results.forEach(r -> r.setFileName(md5ToName.get(r.getFileMd5())));
        } catch (Exception e) {
            logger.error("补充文件名失败", e);
        }
    }

    /**
     * 一次检索的结果和中间阶段；只有 {@link #searchWithTrace} 会填充 candidates 和每一路的召回结果
     *
     * @param candidates            RRF 融合后送去 rerank 的候选（按 RRF 排序）
     * @param bm25Ranked            BM25 这一路的前若干条
     * @param knnRanked             KNN 这一路的前若干条
     * @param thresholdDroppedInTopK 排在前 topK 名、却因为分数低于阈值被丢掉的条数
     */
    public record SearchTrace(List<SearchResult> results,
                              List<SearchResult> candidates,
                              List<SearchResult> bm25Ranked,
                              List<SearchResult> knnRanked,
                              String mode,
                              int thresholdDroppedInTopK) {

        static SearchTrace empty() {
            return new SearchTrace(new ArrayList<>(), List.of(), List.of(), List.of(), null, 0);
        }

        static SearchTrace textOnly(List<SearchResult> results) {
            return new SearchTrace(results, List.of(), List.of(), List.of(), MODE_TEXT_ONLY, 0);
        }
    }

    private record Ranked(List<SearchResult> results, String mode, int thresholdDroppedInTopK) {
    }

    private record RecallOutcome(List<Hit<EsDocument>> hits, String error) {
        static RecallOutcome ok(List<Hit<EsDocument>> hits) {
            return new RecallOutcome(hits, null);
        }

        static RecallOutcome failed(String error) {
            return new RecallOutcome(List.of(), error);
        }

        boolean ok() {
            return error == null;
        }
    }
}
