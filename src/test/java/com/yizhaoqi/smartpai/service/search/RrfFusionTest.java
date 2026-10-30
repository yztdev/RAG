package com.yizhaoqi.smartpai.service.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RrfFusionTest {

    @Test
    void documentRecalledByBothPathsRanksFirst() {
        List<String> bm25 = List.of("a", "b", "c");
        List<String> knn = List.of("c", "d", "a");

        List<RrfFusion.Fused<String>> fused = RrfFusion.fuse(60, Function.identity(), bm25, knn);

        assertEquals(List.of("a", "c", "b", "d"), fused.stream().map(RrfFusion.Fused::key).toList());
        assertEquals(1.0 / 61 + 1.0 / 63, fused.get(0).score(), 1e-12);
        assertArrayEquals(new int[]{1, 3}, fused.get(0).ranks());
    }

    @Test
    void keywordOnlyDocumentSurvivesFusion() {
        // 型号 / 错误码类查询：关键词完全匹配但向量得分低，只在 BM25 一路出现也必须保留
        List<String> bm25 = List.of("error-code-E1024");
        List<String> knn = List.of("x", "y", "z");

        List<RrfFusion.Fused<String>> fused = RrfFusion.fuse(60, Function.identity(), bm25, knn);

        RrfFusion.Fused<String> keywordDoc = fused.stream().filter(f -> f.key().equals("error-code-E1024")).findFirst().orElseThrow();
        assertTrue(keywordDoc.recalledBy(0));
        assertFalse(keywordDoc.recalledBy(1));
        assertEquals("error-code-E1024", fused.get(0).key(), "与 KNN 第一名同分时按首次出现顺序");
    }

    @Test
    void usesOnlyRankNotRawScoreAndIgnoresDuplicatesWithinOnePath() {
        List<String> bm25 = List.of("a", "a", "b");
        List<RrfFusion.Fused<String>> fused = RrfFusion.fuse(60, Function.identity(), bm25);

        assertEquals(2, fused.size());
        assertEquals(1.0 / 61, fused.get(0).score(), 1e-12);
        assertEquals(1.0 / 63, fused.get(1).score(), 1e-12, "b 在该路名次为 3");
    }

    @Test
    void handlesEmptyAndNullLists() {
        assertTrue(RrfFusion.fuse(60, Function.identity(), List.<String>of(), null).isEmpty());
    }

    @Test
    void rejectsNonPositiveK() {
        assertThrows(IllegalArgumentException.class, () -> RrfFusion.fuse(0, Function.identity(), List.of("a")));
    }
}
