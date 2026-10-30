package com.yizhaoqi.smartpai.service.search;

import com.yizhaoqi.smartpai.config.ParentContextProperties;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParentContextExpanderTest {

    private final ParentContextProperties properties = new ParentContextProperties();
    private final DocumentVectorRepository repository = mock(DocumentVectorRepository.class);
    private final List<DocumentVector> chunks = new ArrayList<>();
    private ParentContextExpander expander;

    @BeforeEach
    void setUp() {
        properties.setNeighbourChunks(1);
        properties.setMaxCharsPerHit(1000);
        properties.setMaxTotalChars(5000);
        expander = new ParentContextExpander(properties, repository);
        for (int id = 1; id <= 6; id++) {
            chunks.add(chunk("md5", id, "第" + id + "块。"));
        }
        when(repository.findByFileMd5AndChunkIdBetweenOrderByChunkIdAsc(eq("md5"), anyInt(), anyInt())).thenAnswer(invocation -> {
            int from = invocation.getArgument(1);
            int to = invocation.getArgument(2);
            return chunks.stream().filter(c -> c.getChunkId() >= from && c.getChunkId() <= to).toList();
        });
    }

    @Test
    void attachesNeighboursButKeepsCitationFieldsOnSmallChunk() {
        SearchResult hit = hit("md5", 3, 7);

        List<SearchResult> expanded = expander.expand(List.of(hit));

        SearchResult result = expanded.get(0);
        assertEquals("第2块。\n\n第3块。\n\n第4块。", result.getParentContext());
        assertEquals("md5#2-4", result.getParentKey());
        assertEquals(3, result.getChunkId());
        assertEquals(7, result.getPageNumber());
        assertEquals("anchor-3", result.getAnchorText());
        assertEquals("第3块。", result.getMatchedChunkText(), "引用和证据片段仍指向命中的小分块");
        assertNull(hit.getParentContext(), "原对象不被修改");
    }

    @Test
    void hitsSharingAParentReuseItAndDoNotSpendBudgetTwice() {
        properties.setMaxTotalChars(20);

        List<SearchResult> expanded = expander.expand(List.of(hit("md5", 3, null), hit("md5", 4, null), hit("md5", 6, null)));

        assertEquals("md5#2-4", expanded.get(0).getParentKey());
        assertEquals("md5#2-4", expanded.get(1).getParentKey(), "第 4 块已在第 3 块的父块里，复用同一个父块");
        assertEquals(expanded.get(0).getParentContext(), expanded.get(1).getParentContext());
        assertNull(expanded.get(2).getParentContext(), "总预算用完后剩余命中只给小分块");
        verify(repository, never()).findByFileMd5AndChunkIdBetweenOrderByChunkIdAsc("md5", 3, 5);
    }

    @Test
    void perHitCapShrinksParentAroundTheHit() {
        chunks.set(1, chunk("md5", 2, "很长的前文".repeat(50)));
        properties.setMaxCharsPerHit(12);

        SearchResult result = expander.expand(List.of(hit("md5", 3, null))).get(0);

        assertEquals("第3块。\n\n第4块。", result.getParentContext(), "放不下的前文不扩展，后文照常扩展");
        assertEquals("md5#3-4", result.getParentKey());
    }

    @Test
    void stripsOverlapBetweenNeighbours() {
        chunks.set(3, chunk("md5", 4, "第3块。\n\n第4块正文。"));

        SearchResult result = expander.expand(List.of(hit("md5", 3, null))).get(0);

        assertEquals("第2块。\n\n第3块。\n\n第4块正文。", result.getParentContext());
    }

    @Test
    void disabledOrMissingChunksLeaveHitsUntouched() {
        SearchResult orphan = hit("gone", 1, null);
        when(repository.findByFileMd5AndChunkIdBetweenOrderByChunkIdAsc(eq("gone"), anyInt(), anyInt())).thenReturn(List.of());
        assertSame(orphan, expander.expand(List.of(orphan)).get(0));

        properties.setEnabled(false);
        List<SearchResult> hits = List.of(hit("md5", 3, null));
        assertSame(hits, expander.expand(hits));
    }

    @Test
    void repositoryFailureFallsBackToSmallChunk() {
        when(repository.findByFileMd5AndChunkIdBetweenOrderByChunkIdAsc(eq("broken"), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        SearchResult result = expander.expand(List.of(hit("broken", 1, null))).get(0);

        assertNull(result.getParentContext());
        assertTrue(expander.expand(List.of()).isEmpty());
        verify(repository, never()).findByFileMd5AndChunkIdBetweenOrderByChunkIdAsc(eq("other"), anyInt(), anyInt());
    }

    @Test
    void parentCopyKeepsScoresAndRetrievalMode() {
        SearchResult hit = hit("md5", 3, 1);
        hit.setRawScore(0.031);
        hit.setRetrievalMode("HYBRID_RRF");

        SearchResult copy = hit.withParentContext("父块", "md5#2-4");

        assertEquals(0.031, copy.getRawScore(), 1e-12, "原始分要随父块副本带到引用映射里");
        assertEquals(hit.getScore(), copy.getScore());
        assertEquals("HYBRID_RRF", copy.getRetrievalMode());
    }

    private static SearchResult hit(String md5, int chunkId, Integer page) {
        return new SearchResult(md5, chunkId, "第" + chunkId + "块。", 0.9, "7", "org", false, "doc.pdf",
                page, "anchor-" + chunkId, "HYBRID", null);
    }

    private static DocumentVector chunk(String md5, int id, String text) {
        DocumentVector vector = new DocumentVector();
        vector.setFileMd5(md5);
        vector.setChunkId(id);
        vector.setTextContent(text);
        return vector;
    }
}
