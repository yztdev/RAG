package com.yizhaoqi.smartpai.service.contextual;

import com.yizhaoqi.smartpai.config.ContextualRetrievalProperties;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 入库时为分块补上下文说明（向量化之前调用）。
 * 按 chunkId 顺序每 chunksPerRequest 个分块一批，带上前后相邻分块组成有界窗口，一批一次 LLM 调用；
 * 最多 concurrency 批同时在途（线程池全局共享），生成结果写回 document_vectors.chunk_context。
 * 已有说明的分块跳过，Kafka 重试时不会重复花钱；任何一批失败只记日志，这批分块按原文入索引，不影响入库。
 * 工作线程只调用 LLM，读写数据库都在调用线程里做，reindexDocument 的事务里刚写入的分块也能正常处理。
 */
@Service
public class ChunkContextService {

    private static final Logger logger = LoggerFactory.getLogger(ChunkContextService.class);

    private final ContextualRetrievalProperties properties;
    private final ChunkContextGenerator generator;
    private final DocumentVectorRepository documentVectorRepository;
    private final FileUploadRepository fileUploadRepository;
    private final Executor executor;
    private final ExecutorService ownedExecutor;

    @Autowired
    public ChunkContextService(ContextualRetrievalProperties properties,
                               ChunkContextGenerator generator,
                               DocumentVectorRepository documentVectorRepository,
                               FileUploadRepository fileUploadRepository) {
        this(properties, generator, documentVectorRepository, fileUploadRepository, newPool(properties.getConcurrency()));
    }

    ChunkContextService(ContextualRetrievalProperties properties,
                        ChunkContextGenerator generator,
                        DocumentVectorRepository documentVectorRepository,
                        FileUploadRepository fileUploadRepository,
                        Executor executor) {
        this.properties = properties;
        this.generator = generator;
        this.documentVectorRepository = documentVectorRepository;
        this.fileUploadRepository = fileUploadRepository;
        this.executor = executor;
        this.ownedExecutor = executor instanceof ExecutorService service ? service : null;
    }

    private static ExecutorService newPool(int concurrency) {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(Math.max(concurrency, 1),
                r -> {
                    Thread thread = new Thread(r, "chunk-context-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @PreDestroy
    public void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 为文件中还没有上下文说明的分块生成说明。功能关闭时直接返回。
     */
    public ContextualizationResult ensureContexts(String fileMd5) {
        if (!properties.isEnabled()) {
            return ContextualizationResult.EMPTY;
        }
        String title = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
                .map(FileUpload::getFileName)
                .orElse(fileMd5);
        int batchSize = Math.max(properties.getChunksPerRequest(), 1);
        int maxInFlight = Math.max(properties.getConcurrency(), 1);
        Deque<PendingBatch> inFlight = new ArrayDeque<>();
        Tally tally = new Tally();

        Pageable page = PageRequest.of(0, batchSize);
        while (true) {
            Slice<DocumentVector> slice = documentVectorRepository.findByFileMd5OrderByChunkIdAsc(fileMd5, page);
            List<DocumentVector> pending = slice.getContent().stream()
                    .filter(chunk -> chunk.getChunkContext() == null || chunk.getChunkContext().isBlank())
                    .filter(chunk -> chunk.getTextContent() != null && !chunk.getTextContent().isBlank())
                    .toList();
            if (!pending.isEmpty()) {
                inFlight.addLast(submit(fileMd5, title, pending));
                tally.requested += pending.size();
                while (inFlight.size() >= maxInFlight) {
                    apply(fileMd5, inFlight.removeFirst(), tally);
                }
            }
            if (!slice.hasNext()) {
                break;
            }
            page = slice.nextPageable();
        }
        while (!inFlight.isEmpty()) {
            apply(fileMd5, inFlight.removeFirst(), tally);
        }

        ContextualizationResult result = new ContextualizationResult(tally.requested, tally.generated, tally.failedBatches);
        logger.info("分块上下文说明生成完成: fileMd5={}, 待生成={}, 已生成={}, 失败批次={}",
                fileMd5, result.requestedChunks(), result.generatedChunks(), result.failedBatches());
        return result;
    }

    private PendingBatch submit(String fileMd5, String title, List<DocumentVector> pending) {
        int first = pending.get(0).getChunkId();
        int last = pending.get(pending.size() - 1).getChunkId();
        int neighbours = Math.max(properties.getNeighbourChunks(), 0);
        List<DocumentVector> window = documentVectorRepository.findByFileMd5AndChunkIdBetweenOrderByChunkIdAsc(
                fileMd5, first - neighbours, last + neighbours);
        Set<Integer> targetIds = new LinkedHashSet<>();
        pending.forEach(chunk -> targetIds.add(chunk.getChunkId()));
        String message = ContextWindowBuilder.build(title, window.isEmpty() ? pending : window, targetIds,
                Math.max(properties.getMaxWindowChars(), 1));
        CompletableFuture<Map<Integer, String>> future = CompletableFuture.supplyAsync(() -> {
            try {
                return generator.generate(message, targetIds);
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }, executor);
        return new PendingBatch(pending, future);
    }

    private void apply(String fileMd5, PendingBatch batch, Tally tally) {
        Map<Integer, String> contexts;
        try {
            contexts = batch.future().join();
        } catch (Exception e) {
            tally.failedBatches++;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            logger.warn("分块上下文说明生成失败，这批分块按原文入索引: fileMd5={}, chunks={}-{}, error={}",
                    fileMd5, batch.chunks().get(0).getChunkId(),
                    batch.chunks().get(batch.chunks().size() - 1).getChunkId(), cause.getMessage());
            return;
        }
        List<DocumentVector> updated = batch.chunks().stream()
                .filter(chunk -> contexts.containsKey(chunk.getChunkId()))
                .toList();
        updated.forEach(chunk -> chunk.setChunkContext(contexts.get(chunk.getChunkId())));
        if (!updated.isEmpty()) {
            documentVectorRepository.saveAll(updated);
        }
        tally.generated += updated.size();
    }

    public record ContextualizationResult(int requestedChunks, int generatedChunks, int failedBatches) {
        static final ContextualizationResult EMPTY = new ContextualizationResult(0, 0, 0);
    }

    private record PendingBatch(List<DocumentVector> chunks, CompletableFuture<Map<Integer, String>> future) {
    }

    private static final class Tally {
        private int requested;
        private int generated;
        private int failedBatches;
    }
}
