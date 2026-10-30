package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.chunk.ContextualChunkText;
import com.yizhaoqi.smartpai.service.contextual.ChunkContextService;
import com.yizhaoqi.smartpai.service.reliability.VectorizationLeaseLostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

// 向量化服务类
@Service
public class VectorizationService {

    private static final Logger logger = LoggerFactory.getLogger(VectorizationService.class);

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private ModelProviderConfigService modelProviderConfigService;

    @Autowired
    private ChunkContextService chunkContextService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    // 每批分块数：分页读分块 → 调 embedding → 立即写 ES，整份文件的向量不在内存里攒着
    @Value("${embedding.api.batch-size:10}")
    private int batchSize = 10;

    /**
     * 执行向量化操作
     * @param fileMd5 文件指纹
     * @param userId 上传用户ID
     * @param orgTag 组织标签
     * @param isPublic 是否公开
     */
    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, userId);
    }

    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, requesterId);
    }

    /**
     * 按批向量化并写入 Elasticsearch。
     * 文档 ID 为 fileMd5_chunkId：中途失败后 Kafka 重试会覆盖已写入的批次，不会产生重复文档。
     */
    public VectorizationUsageResult vectorizeWithUsage(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        return vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, requesterId, () -> { });
    }

    /**
     * @param onBatchIndexed 每批分块写入 ES 后回调，消费者用它刷新任务心跳；回调抛出的异常会中止向量化
     */
    public VectorizationUsageResult vectorizeWithUsage(String fileMd5, String userId, String orgTag, boolean isPublic,
                                                       String requesterId, Runnable onBatchIndexed) {
        try {
            logger.info("开始向量化文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}",
                       fileMd5, userId, orgTag, isPublic);

            // 整份文件固定使用同一个模型，避免处理中途切换激活模型导致同一文件混用两套向量
            ModelProviderConfigService.ActiveProviderView provider =
                    modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
            String modelVersion = EmbeddingClient.modelVersionOf(provider);
            boolean contextual = prepareChunkContexts(fileMd5);
            String fileName = resolveFileName(fileMd5, userId);

            int totalTokens = 0;
            int totalChunks = 0;
            Pageable page = PageRequest.of(0, Math.max(batchSize, 1));
            while (true) {
                Slice<DocumentVector> slice = documentVectorRepository.findByFileMd5OrderByChunkIdAsc(fileMd5, page);
                List<DocumentVector> chunks = slice.getContent();
                if (!chunks.isEmpty()) {
                    totalTokens += embedAndIndexBatch(fileMd5, fileName, chunks, userId, orgTag, isPublic, requesterId, provider,
                            modelVersion, contextual);
                    totalChunks += chunks.size();
                    onBatchIndexed.run();
                }
                if (!slice.hasNext()) {
                    break;
                }
                page = slice.nextPageable();
            }

            if (totalChunks == 0) {
                logger.warn("未找到分块内容，fileMd5: {}", fileMd5);
            } else {
                logger.info("向量化完成，fileMd5: {}, chunks: {}, tokens: {}", fileMd5, totalChunks, totalTokens);
            }
            return new VectorizationUsageResult(totalTokens, totalChunks, modelVersion);
        } catch (VectorizationLeaseLostException e) {
            throw e;
        } catch (Exception e) {
            logger.error("向量化失败，fileMd5: {}", fileMd5, e);
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                throw new RuntimeException("向量化失败", e);
            }
            throw new RuntimeException("向量化失败: " + message, e);
        }
    }

    /**
     * 上下文检索开启时，先为还没有说明的分块生成上下文说明（写回 MySQL）；生成失败只记日志，按原文入索引
     *
     * @return 本次建索引是否使用上下文说明
     */
    private boolean prepareChunkContexts(String fileMd5) {
        if (chunkContextService == null || !chunkContextService.isEnabled()) {
            return false;
        }
        try {
            chunkContextService.ensureContexts(fileMd5);
        } catch (Exception e) {
            logger.warn("分块上下文说明生成异常，已生成的说明照常使用，其余分块按原文入索引: fileMd5={}, error={}",
                    fileMd5, e.getMessage());
        }
        return true;
    }

    /**
     * 文件名单独写进索引参与关键词打分；优先取上传者那条记录，取不到时用该 MD5 最新的记录
     */
    private String resolveFileName(String fileMd5, String userId) {
        if (fileUploadRepository == null) {
            return null;
        }
        return fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId)
                .or(() -> fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5))
                .map(FileUpload::getFileName)
                .orElse(null);
    }

    private int embedAndIndexBatch(String fileMd5,
                                   String fileName,
                                   List<DocumentVector> chunks,
                                   String userId,
                                   String orgTag,
                                   boolean isPublic,
                                   String requesterId,
                                   ModelProviderConfigService.ActiveProviderView provider,
                                   String modelVersion,
                                   boolean contextual) {
        // 上下文说明拼在原文前面做 embedding；ES 里 textContent 仍存原文，说明单独存 chunkContext 参与 BM25
        List<String> texts = chunks.stream()
                .map(chunk -> ContextualChunkText.compose(contextual ? chunk.getChunkContext() : null, chunk.getTextContent()))
                .toList();
        EmbeddingClient.EmbeddingUsageResult embeddingResult = embeddingClient.embedWithProvider(
                texts, requesterId, EmbeddingClient.UsageType.UPLOAD, provider);
        List<float[]> vectors = embeddingResult.vectors();
        if (vectors.size() != chunks.size()) {
            throw new IllegalStateException(String.format("embedding 返回数量不一致: expected=%d, actual=%d",
                    chunks.size(), vectors.size()));
        }

        List<EsDocument> esDocuments = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            DocumentVector chunk = chunks.get(i);
            esDocuments.add(new EsDocument(
                    fileMd5 + "_" + chunk.getChunkId(),
                    fileMd5,
                    chunk.getChunkId(),
                    chunk.getTextContent(),
                    contextual ? chunk.getChunkContext() : null,
                    chunk.getPageNumber(),
                    chunk.getAnchorText(),
                    vectors.get(i),
                    modelVersion,
                    userId,
                    orgTag,
                    isPublic
            ));
        }
        esDocuments.forEach(doc -> doc.setFileName(fileName));
        elasticsearchService.bulkIndex(esDocuments);
        return embeddingResult.totalTokens();
    }

    public record VectorizationUsageResult(int actualEmbeddingTokens, int actualChunkCount, String modelVersion) {
    }
}
