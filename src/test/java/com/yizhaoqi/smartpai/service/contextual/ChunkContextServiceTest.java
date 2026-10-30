package com.yizhaoqi.smartpai.service.contextual;

import com.yizhaoqi.smartpai.config.ContextualRetrievalProperties;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChunkContextServiceTest {

    private final ContextualRetrievalProperties properties = new ContextualRetrievalProperties();
    private final ChunkContextGenerator generator = mock(ChunkContextGenerator.class);
    private final DocumentVectorRepository repository = mock(DocumentVectorRepository.class);
    private final FileUploadRepository fileUploadRepository = mock(FileUploadRepository.class);
    private final List<DocumentVector> chunks = new ArrayList<>();
    private ChunkContextService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setChunksPerRequest(4);
        properties.setNeighbourChunks(1);
        properties.setConcurrency(2);
        service = new ChunkContextService(properties, generator, repository, fileUploadRepository, Runnable::run);

        FileUpload upload = new FileUpload();
        upload.setFileMd5("md5");
        upload.setFileName("部署手册.pdf");
        when(fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc("md5")).thenReturn(Optional.of(upload));
        when(repository.findByFileMd5OrderByChunkIdAsc(eq("md5"), any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(1);
            int from = (int) Math.min(pageable.getOffset(), chunks.size());
            int to = Math.min(from + pageable.getPageSize(), chunks.size());
            return new SliceImpl<>(List.copyOf(chunks.subList(from, to)), pageable, to < chunks.size());
        });
        when(repository.findByFileMd5AndChunkIdBetweenOrderByChunkIdAsc(eq("md5"), anyInt(), anyInt())).thenAnswer(invocation -> {
            int from = invocation.getArgument(1);
            int to = invocation.getArgument(2);
            return chunks.stream().filter(c -> c.getChunkId() >= from && c.getChunkId() <= to).toList();
        });
    }

    @Test
    void generatesContextsBatchByBatchAndSavesThem() throws Exception {
        addChunks(10);
        when(generator.generate(anyString(), any())).thenAnswer(invocation -> {
            Set<Integer> ids = invocation.getArgument(1);
            Map<Integer, String> contexts = new HashMap<>();
            ids.forEach(id -> contexts.put(id, "说明-" + id));
            return contexts;
        });

        ChunkContextService.ContextualizationResult result = service.ensureContexts("md5");

        assertEquals(10, result.requestedChunks());
        assertEquals(10, result.generatedChunks());
        assertEquals(0, result.failedBatches());
        assertEquals("说明-7", chunks.get(6).getChunkContext());
        verify(generator, times(3)).generate(anyString(), any());
        // 第二批 5-8 的窗口带上前后各 1 个相邻分块
        verify(repository).findByFileMd5AndChunkIdBetweenOrderByChunkIdAsc("md5", 4, 9);
        verify(repository, times(3)).saveAll(any());

        ArgumentCaptor<String> windows = ArgumentCaptor.forClass(String.class);
        verify(generator, times(3)).generate(windows.capture(), any());
        assertTrue(windows.getAllValues().get(0).startsWith("文档标题：部署手册.pdf"));
    }

    @Test
    void failedBatchFallsBackWithoutFailingIngestionAndOtherBatchesContinue() throws Exception {
        addChunks(8);
        when(generator.generate(anyString(), any()))
                .thenThrow(new IllegalStateException("llm down"))
                .thenReturn(Map.of(5, "说明-5", 6, "说明-6"));

        ChunkContextService.ContextualizationResult result = service.ensureContexts("md5");

        assertEquals(8, result.requestedChunks());
        assertEquals(2, result.generatedChunks(), "模型漏掉的 7、8 按原文入索引");
        assertEquals(1, result.failedBatches());
        assertNull(chunks.get(0).getChunkContext());
        assertEquals("说明-5", chunks.get(4).getChunkContext());
        assertNull(chunks.get(6).getChunkContext());
    }

    @Test
    void skipsChunksThatAlreadyHaveContexts() throws Exception {
        addChunks(4);
        chunks.forEach(c -> c.setChunkContext("已有"));

        ChunkContextService.ContextualizationResult result = service.ensureContexts("md5");

        assertEquals(0, result.requestedChunks());
        verify(generator, never()).generate(anyString(), any());
    }

    @Test
    void disabledDoesNothing() {
        properties.setEnabled(false);

        assertEquals(0, service.ensureContexts("md5").requestedChunks());
        verifyNoInteractions(generator, repository, fileUploadRepository);
    }

    private void addChunks(int count) {
        IntStream.rangeClosed(1, count).forEach(id -> {
            DocumentVector vector = new DocumentVector();
            vector.setFileMd5("md5");
            vector.setChunkId(id);
            vector.setTextContent("分块" + id + "原文。");
            chunks.add(vector);
        });
    }
}
