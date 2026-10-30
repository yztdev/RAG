package com.yizhaoqi.smartpai.service.conflict;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyValueConflictAnalyzerTest {

    private static final double FLOOR = 0.8;

    @Test
    void sameConfigItemWithDifferentValueIsConflict() {
        var analysis = KeyValueConflictAnalyzer.analyze(
                "订单服务的 HTTP 端口配置为 8080，连接超时时间为 30 秒，重试次数为 3 次。",
                "订单服务的 HTTP 端口配置为 9090，连接超时时间为 30 秒，重试次数为 3 次。",
                FLOOR);

        assertTrue(analysis.conflict());
        assertEquals(Set.of("number:8080"), analysis.onlyInFirst());
        assertEquals(Set.of("number:9090"), analysis.onlyInSecond());
        assertEquals(1.0, analysis.maskedSimilarity(), 1e-9);
    }

    @Test
    void textsIdenticalAfterNormalizationAreDuplicatesNotConflicts() {
        var analysis = KeyValueConflictAnalyzer.analyze(
                "端口：８０８０，超时 ３０ 秒。",
                "端口: 8080 ,超时30秒.",
                FLOOR);

        assertFalse(analysis.conflict());
    }

    @Test
    void caseAndWhitespaceDifferencesAreIgnored() {
        var analysis = KeyValueConflictAnalyzer.analyze("Max Pool Size = 20", "max pool   size=20", FLOOR);

        assertFalse(analysis.conflict());
    }

    @Test
    void versionStringsAreComparedAsVersionsNotNumbers() {
        var extraction = KeyValueConflictAnalyzer.extract("客户端需要升级到 v2.3.1，服务端保持 1.8.0。");
        assertEquals(Set.of("version:2.3.1", "version:1.8.0"), extraction.values());

        var analysis = KeyValueConflictAnalyzer.analyze(
                "客户端需要升级到 v2.3.1，服务端保持 1.8.0。",
                "客户端需要升级到 v2.4.0，服务端保持 1.8.0。",
                FLOOR);
        assertTrue(analysis.conflict());
        assertEquals(Set.of("version:2.3.1"), analysis.onlyInFirst());
        assertEquals(Set.of("version:2.4.0"), analysis.onlyInSecond());
    }

    @Test
    void equivalentDatesInDifferentFormatsAreNotConflicts() {
        var analysis = KeyValueConflictAnalyzer.analyze(
                "新的报销标准自 2024年1月5日 起生效。",
                "新的报销标准自 2024-01-05 起生效。",
                FLOOR);

        assertFalse(analysis.conflict());
        assertEquals("关键值一致", analysis.reason());
    }

    @Test
    void differentDatesAreConflicts() {
        var analysis = KeyValueConflictAnalyzer.analyze(
                "新的报销标准自 2024年1月5日 起生效。",
                "新的报销标准自 2024年3月1日 起生效。",
                FLOOR);

        assertTrue(analysis.conflict());
        assertEquals(Set.of("date:2024-1-5"), analysis.onlyInFirst());
        assertEquals(Set.of("date:2024-3-1"), analysis.onlyInSecond());
    }

    @Test
    void numberFormattingDifferencesAreNotConflicts() {
        var analysis = KeyValueConflictAnalyzer.analyze(
                "单笔转账上限为 1,000 元，费率 0.50%。",
                "单笔转账上限为 1000 元，费率 0.5%。",
                FLOOR);

        assertFalse(analysis.conflict());
    }

    @Test
    void differentTopicsAreRejectedByMaskedSimilarity() {
        var analysis = KeyValueConflictAnalyzer.analyze(
                "订单服务的 HTTP 端口配置为 8080。",
                "年假天数按工龄计算，满 10 年可休 15 天。",
                FLOOR);

        assertFalse(analysis.conflict());
        assertTrue(analysis.maskedSimilarity() < FLOOR);
    }

    @Test
    void sideWithoutKeyValuesIsNotConflict() {
        var analysis = KeyValueConflictAnalyzer.analyze(
                "订单服务的 HTTP 端口需要在配置中心设置。",
                "订单服务的 HTTP 端口需要在配置中心设置，默认 8080。",
                0.5);

        assertFalse(analysis.conflict());
    }

    @Test
    void blankTextIsNotConflict() {
        assertFalse(KeyValueConflictAnalyzer.analyze("", "端口 8080", FLOOR).conflict());
        assertFalse(KeyValueConflictAnalyzer.analyze(null, "端口 8080", FLOOR).conflict());
    }

    @Test
    void diceSimilarityBounds() {
        assertEquals(1.0, KeyValueConflictAnalyzer.diceSimilarity("abc", "abc"), 1e-9);
        assertEquals(0.0, KeyValueConflictAnalyzer.diceSimilarity("ab", "cd"), 1e-9);
        assertEquals(1.0 / 3, KeyValueConflictAnalyzer.diceSimilarity("abcd", "abxy"), 1e-9);
    }
}
