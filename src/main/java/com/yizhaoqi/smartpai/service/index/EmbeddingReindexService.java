package com.yizhaoqi.smartpai.service.index;

import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.config.ContextualRetrievalProperties;
import com.yizhaoqi.smartpai.config.EmbeddingReindexProperties;
import com.yizhaoqi.smartpai.consumer.FileProcessingListenerGate;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.exception.InsufficientBalanceException;
import com.yizhaoqi.smartpai.exception.RateLimitExceededException;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.ElasticsearchService;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService.ActiveProviderView;
import com.yizhaoqi.smartpai.service.chunk.ContextualChunkText;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * embedding 模型升级的全量重建：
 * 新建版本索引 → 用目标模型逐批重新生成向量写入 → 暂停文件消费做追平 → 校验文档数与抽样问题
 * → 原子切换别名并同步切换激活的 embedding provider → 旧索引保留用于回滚。
 * 文档 ID 由 fileMd5 + chunkId 决定，且已完成的文件会跳过，整个流程幂等，失败后重跑即可续建。
 */
@Service
public class EmbeddingReindexService {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingReindexService.class);
    private static final int VALIDATION_PROBE_K = 3;

    private final KnowledgeIndexManager indexManager;
    private final ElasticsearchService elasticsearchService;
    private final EmbeddingClient embeddingClient;
    private final ModelProviderConfigService providerConfigService;
    private final FileUploadRepository fileUploadRepository;
    private final DocumentVectorRepository documentVectorRepository;
    private final FileProcessingListenerGate listenerGate;
    private final EmbeddingReindexProperties properties;
    private final ContextualRetrievalProperties contextualProperties;
    private final ExecutorService executor;
    private final AtomicReference<RebuildProgress> current = new AtomicReference<>();

    @Autowired
    public EmbeddingReindexService(KnowledgeIndexManager indexManager,
                                   ElasticsearchService elasticsearchService,
                                   EmbeddingClient embeddingClient,
                                   ModelProviderConfigService providerConfigService,
                                   FileUploadRepository fileUploadRepository,
                                   DocumentVectorRepository documentVectorRepository,
                                   FileProcessingListenerGate listenerGate,
                                   EmbeddingReindexProperties properties,
                                   ContextualRetrievalProperties contextualProperties) {
        this(indexManager, elasticsearchService, embeddingClient, providerConfigService, fileUploadRepository,
                documentVectorRepository, listenerGate, properties, contextualProperties,
                Executors.newSingleThreadExecutor(r -> new Thread(r, "embedding-reindex")));
    }

    EmbeddingReindexService(KnowledgeIndexManager indexManager,
                            ElasticsearchService elasticsearchService,
                            EmbeddingClient embeddingClient,
                            ModelProviderConfigService providerConfigService,
                            FileUploadRepository fileUploadRepository,
                            DocumentVectorRepository documentVectorRepository,
                            FileProcessingListenerGate listenerGate,
                            EmbeddingReindexProperties properties,
                            ContextualRetrievalProperties contextualProperties,
                            ExecutorService executor) {
        this.indexManager = indexManager;
        this.elasticsearchService = elasticsearchService;
        this.embeddingClient = embeddingClient;
        this.providerConfigService = providerConfigService;
        this.fileUploadRepository = fileUploadRepository;
        this.documentVectorRepository = documentVectorRepository;
        this.listenerGate = listenerGate;
        this.properties = properties;
        this.contextualProperties = contextualProperties;
        this.executor = executor;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 提交重建任务（异步执行）。同一时间只允许一个任务。
     *
     * @param targetProvider 目标 embedding provider 编码，需已在模型配置中保存好 API 地址、模型、维度和 Key
     * @param fresh          true 时删除同名目标索引从头重建；false 时在已有目标索引上续建
     */
    public synchronized RebuildStatus startRebuild(String targetProvider, boolean fresh, String operator) {
        RebuildProgress running = current.get();
        if (running != null && running.state == RebuildState.RUNNING) {
            throw new CustomException("已有 embedding 重建任务在运行", HttpStatus.CONFLICT);
        }

        ActiveProviderView target = providerConfigService.getProvider(ModelProviderConfigService.SCOPE_EMBEDDING, targetProvider);
        if (target.dimension() == null || target.dimension() <= 0) {
            throw new CustomException("目标 provider 未配置向量维度", HttpStatus.BAD_REQUEST);
        }
        ActiveProviderView previous = providerConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
        String targetVersion = EmbeddingClient.modelVersionOf(target);
        String targetIndex = KnowledgeIndexNames.versionedIndexName(targetVersion);
        String liveIndex = resolveLiveIndex();
        if (targetIndex.equals(liveIndex)) {
            throw new CustomException("目标模型与线上索引一致（" + targetIndex + "），无需重建", HttpStatus.CONFLICT);
        }

        RebuildProgress progress = new RebuildProgress(target, previous, targetVersion, targetIndex, liveIndex, operator);
        current.set(progress);
        executor.execute(() -> runRebuild(progress, fresh));
        logger.warn("embedding 重建任务已提交: operator={}, target={}, targetIndex={}, liveIndex={}, fresh={}",
                operator, targetVersion, targetIndex, liveIndex, fresh);
        return progress.snapshot();
    }

    public Optional<RebuildStatus> getStatus() {
        return Optional.ofNullable(current.get()).map(RebuildProgress::snapshot);
    }

    /**
     * 回滚：把别名切回保留的旧索引，并把激活的 embedding provider 切回生成该索引的模型
     */
    public synchronized void rollback(String index, String operator) throws Exception {
        RebuildProgress running = current.get();
        if (running != null && running.state == RebuildState.RUNNING) {
            throw new CustomException("重建任务运行中，不能回滚", HttpStatus.CONFLICT);
        }
        if (!index.startsWith(KnowledgeIndexNames.VERSIONED_PREFIX) || !indexManager.indexExists(index)) {
            throw new CustomException("回滚目标索引不存在: " + index, HttpStatus.BAD_REQUEST);
        }
        Map<String, String> meta = indexManager.readMeta(index);
        String provider = meta.get(KnowledgeIndexManager.META_PROVIDER);
        String modelVersion = meta.get(KnowledgeIndexManager.META_MODEL_VERSION);
        if (provider == null || modelVersion == null) {
            throw new CustomException("索引缺少模型元信息，无法确定回滚后的查询模型: " + index, HttpStatus.BAD_REQUEST);
        }
        ActiveProviderView providerView = providerConfigService.getProvider(ModelProviderConfigService.SCOPE_EMBEDDING, provider);
        if (!modelVersion.equals(EmbeddingClient.modelVersionOf(providerView))) {
            throw new CustomException(String.format("provider %s 当前配置为 %s，与索引模型 %s 不一致，请先恢复该 provider 配置",
                    provider, EmbeddingClient.modelVersionOf(providerView), modelVersion), HttpStatus.CONFLICT);
        }
        indexManager.switchAlias(index);
        providerConfigService.activateEmbeddingProviderAfterReindex(provider, operator);
        logger.warn("embedding 索引已回滚: operator={}, index={}, provider={}", operator, index, provider);
    }

    void runRebuild(RebuildProgress progress, boolean fresh) {
        try {
            prepareTargetIndex(progress, fresh);
            progress.phase("全量重建");
            rebuildAllFiles(progress);

            // 暂停文件处理消费，压力留在 Kafka；追平重建期间新上传的文件后再校验、切换
            listenerGate.pause(FileProcessingListenerGate.REASON_INDEX_SWITCH);
            try {
                progress.phase("追平增量");
                Set<String> files = rebuildAllFiles(progress);
                progress.phase("校验");
                validate(progress, files);
                progress.phase("切换别名");
                switchAliasAndProvider(progress);
                // 切换前仍在处理中的任务可能已用旧模型写入别名（即新索引），按 modelVersion 再追平一次
                progress.phase("切换后追平");
                rebuildAllFiles(progress);
            } finally {
                listenerGate.resume(FileProcessingListenerGate.REASON_INDEX_SWITCH);
            }
            progress.succeed();
            logger.warn("embedding 重建完成: targetIndex={}, previousIndex={}（已保留用于回滚）",
                    progress.targetIndex, progress.previousIndex);
        } catch (Exception e) {
            progress.fail(e);
            logger.error("embedding 重建失败，线上别名保持不变（除非已在切换阶段之后）: targetIndex={}", progress.targetIndex, e);
        }
    }

    private void prepareTargetIndex(RebuildProgress progress, boolean fresh) throws Exception {
        progress.phase("准备目标索引");
        if (fresh && indexManager.indexExists(progress.targetIndex)) {
            indexManager.deleteIndex(progress.targetIndex);
        }
        if (!indexManager.indexExists(progress.targetIndex)) {
            indexManager.createIndex(progress.targetIndex, progress.target.dimension(), Map.of(
                    KnowledgeIndexManager.META_PROVIDER, progress.target.provider(),
                    KnowledgeIndexManager.META_MODEL_VERSION, progress.targetVersion,
                    KnowledgeIndexManager.META_CREATED_AT, Instant.now().toString()
            ), false);
        } else {
            logger.info("目标索引已存在，在其基础上续建: {}", progress.targetIndex);
        }
    }

    /**
     * @return 本轮纳入重建的文件（有分块的）
     */
    private Set<String> rebuildAllFiles(RebuildProgress progress) throws Exception {
        indexManager.refresh(progress.targetIndex);
        Set<String> fileMd5s = listIndexableFiles();
        progress.startPass(fileMd5s.size());
        Set<String> included = new LinkedHashSet<>();
        for (String fileMd5 : fileMd5s) {
            if (rebuildFile(progress, fileMd5)) {
                included.add(fileMd5);
            }
            progress.processedFiles++;
        }
        return included;
    }

    private Set<String> listIndexableFiles() {
        Set<String> md5s = new LinkedHashSet<>();
        for (FileUpload upload : fileUploadRepository.findAll()) {
            if (upload.getStatus() == FileUpload.STATUS_COMPLETED
                    && !FileUpload.VECTORIZATION_STATUS_FAILED.equals(upload.getVectorizationStatus())) {
                md5s.add(upload.getFileMd5());
            }
        }
        return md5s;
    }

    /**
     * @return 文件是否有分块（纳入校验）
     */
    boolean rebuildFile(RebuildProgress progress, String fileMd5) throws Exception {
        List<DocumentVector> chunks = documentVectorRepository.findByFileMd5OrderByChunkIdAsc(fileMd5);
        if (chunks.isEmpty()) {
            progress.skippedFiles++;
            return false;
        }
        long total = elasticsearchService.countByFileMd5(progress.targetIndex, fileMd5);
        long withTargetModel = elasticsearchService.countByFileMd5AndModelVersion(progress.targetIndex, fileMd5, progress.targetVersion);
        if (total == chunks.size() && withTargetModel == chunks.size()) {
            return true;
        }

        elasticsearchService.deleteByFileMd5(progress.targetIndex, fileMd5);
        // 上下文说明只和分块内容有关、与 embedding 模型无关，重建时复用 MySQL 里已生成的说明，不重新调用 LLM；
        // 拼法与入库链路一致（ContextualChunkText），功能关闭时两边都按原文建索引
        boolean contextual = contextualProperties != null && contextualProperties.isEnabled();
        String fileName = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
                .map(FileUpload::getFileName)
                .orElse(null);
        int batchSize = Math.max(properties.getBatchSize(), 1);
        for (int start = 0; start < chunks.size(); start += batchSize) {
            List<DocumentVector> batch = chunks.subList(start, Math.min(start + batchSize, chunks.size()));
            List<String> texts = batch.stream()
                    .map(chunk -> ContextualChunkText.compose(contextual ? chunk.getChunkContext() : null, chunk.getTextContent()))
                    .toList();
            List<float[]> vectors = embedWithRetry(texts, progress.target);
            if (vectors.size() != batch.size()) {
                throw new IllegalStateException(String.format("embedding 返回数量不一致: fileMd5=%s, expected=%d, actual=%d",
                        fileMd5, batch.size(), vectors.size()));
            }
            List<EsDocument> docs = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                DocumentVector chunk = batch.get(i);
                docs.add(new EsDocument(
                        fileMd5 + "_" + chunk.getChunkId(),
                        fileMd5,
                        chunk.getChunkId(),
                        chunk.getTextContent(),
                        contextual ? chunk.getChunkContext() : null,
                        chunk.getPageNumber(),
                        chunk.getAnchorText(),
                        vectors.get(i),
                        progress.targetVersion,
                        chunk.getUserId(),
                        chunk.getOrgTag(),
                        chunk.isPublic()
                ));
            }
            docs.forEach(doc -> doc.setFileName(fileName));
            // 拿到向量立即写入，不在内存里攒整份文件
            elasticsearchService.bulkIndex(progress.targetIndex, docs);
            progress.indexedChunks += docs.size();
        }
        return true;
    }

    private List<float[]> embedWithRetry(List<String> texts, ActiveProviderView target) throws InterruptedException {
        int attempt = 0;
        while (true) {
            try {
                return embeddingClient.embedWithProvider(texts, properties.getRequesterId(),
                        EmbeddingClient.UsageType.UPLOAD, target).vectors();
            } catch (RuntimeException e) {
                if (!isRateLimited(e) || attempt >= properties.getRateLimitRetries()) {
                    throw e;
                }
                attempt++;
                logger.warn("embedding 重建触发限流，{} 秒后第 {} 次重试: {}",
                        properties.getRateLimitBackoffSeconds(), attempt, e.getMessage());
                Thread.sleep(properties.getRateLimitBackoffSeconds() * 1000L);
            }
        }
    }

    private static boolean isRateLimited(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            // 余额不足等多久都一样，不按限流重试
            if (t instanceof InsufficientBalanceException) {
                return false;
            }
            if (t instanceof RateLimitExceededException) {
                return true;
            }
        }
        return false;
    }

    void validate(RebuildProgress progress, Set<String> files) throws Exception {
        indexManager.refresh(progress.targetIndex);
        long expected = 0;
        for (String fileMd5 : files) {
            expected += documentVectorRepository.countByFileMd5(fileMd5);
        }
        long actual = indexManager.count(progress.targetIndex);
        progress.expectedChunks = expected;
        progress.actualChunks = actual;
        if (expected != actual) {
            throw new IllegalStateException(String.format(
                    "文档数校验失败：期望 %d，新索引实际 %d。可带 fresh=true 从头重建", expected, actual));
        }

        for (String query : properties.getValidationQueries()) {
            List<float[]> vectors = embeddingClient.embedWithProvider(List.of(query), properties.getRequesterId(),
                    EmbeddingClient.UsageType.QUERY, progress.target).vectors();
            List<Float> vector = new ArrayList<>(vectors.get(0).length);
            for (float v : vectors.get(0)) {
                vector.add(v);
            }
            if (indexManager.knnProbe(progress.targetIndex, vector, VALIDATION_PROBE_K) == 0) {
                throw new IllegalStateException("抽样问题在新索引上没有召回结果: " + query);
            }
        }
        logger.info("新索引校验通过: index={}, docs={}, sampleQueries={}", progress.targetIndex, actual,
                properties.getValidationQueries().size());
    }

    private void switchAliasAndProvider(RebuildProgress progress) throws Exception {
        String rollbackIndex = progress.previousIndex;
        if (indexManager.hasLegacyConcreteIndex()) {
            // 旧版同名物理索引无法和别名共存，先完整备份作为回滚目标，再在切换请求里移除
            rollbackIndex = indexManager.backupLegacyIndex(Map.of(
                    KnowledgeIndexManager.META_PROVIDER, progress.previous.provider(),
                    KnowledgeIndexManager.META_MODEL_VERSION, EmbeddingClient.modelVersionOf(progress.previous),
                    KnowledgeIndexManager.META_CREATED_AT, Instant.now().toString()
            ));
            progress.previousIndex = rollbackIndex;
        }
        indexManager.switchAlias(progress.targetIndex);
        try {
            providerConfigService.activateEmbeddingProviderAfterReindex(progress.target.provider(), progress.operator);
        } catch (Exception e) {
            // provider 切换失败时别名切回去，保证查询模型与索引模型一致
            if (rollbackIndex != null) {
                indexManager.switchAlias(rollbackIndex);
            }
            throw new IllegalStateException("切换激活的 embedding provider 失败，已回滚别名: " + e.getMessage(), e);
        }
    }

    private String resolveLiveIndex() {
        try {
            return indexManager.currentAliasTarget().orElse(KnowledgeIndexNames.ALIAS);
        } catch (Exception e) {
            throw new CustomException("读取线上索引失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public enum RebuildState {
        RUNNING, SUCCEEDED, FAILED
    }

    public record RebuildStatus(
            RebuildState state,
            String phase,
            String targetProvider,
            String targetModelVersion,
            String targetIndex,
            String previousIndex,
            int totalFiles,
            int processedFiles,
            int skippedFiles,
            long indexedChunks,
            long expectedChunks,
            long actualChunks,
            String message,
            Instant startedAt,
            Instant finishedAt
    ) {
    }

    static final class RebuildProgress {
        private final ActiveProviderView target;
        private final ActiveProviderView previous;
        private final String targetVersion;
        private final String targetIndex;
        private final String operator;
        private final Instant startedAt = Instant.now();
        private volatile String previousIndex;
        private volatile RebuildState state = RebuildState.RUNNING;
        private volatile String phase = "排队中";
        private volatile int totalFiles;
        private volatile int processedFiles;
        private volatile int skippedFiles;
        private volatile long indexedChunks;
        private volatile long expectedChunks;
        private volatile long actualChunks;
        private volatile String message;
        private volatile Instant finishedAt;

        RebuildProgress(ActiveProviderView target, ActiveProviderView previous, String targetVersion,
                        String targetIndex, String previousIndex, String operator) {
            this.target = target;
            this.previous = previous;
            this.targetVersion = targetVersion;
            this.targetIndex = targetIndex;
            this.previousIndex = previousIndex;
            this.operator = operator;
        }

        void phase(String phase) {
            this.phase = phase;
        }

        void startPass(int totalFiles) {
            this.totalFiles = totalFiles;
            this.processedFiles = 0;
            this.skippedFiles = 0;
        }

        void succeed() {
            this.state = RebuildState.SUCCEEDED;
            this.phase = "完成";
            this.finishedAt = Instant.now();
        }

        void fail(Exception e) {
            this.state = RebuildState.FAILED;
            this.message = e.getMessage();
            this.finishedAt = Instant.now();
        }

        RebuildStatus snapshot() {
            return new RebuildStatus(state, phase, target.provider(), targetVersion, targetIndex, previousIndex,
                    totalFiles, processedFiles, skippedFiles, indexedChunks, expectedChunks, actualChunks,
                    message, startedAt, finishedAt);
        }
    }
}
