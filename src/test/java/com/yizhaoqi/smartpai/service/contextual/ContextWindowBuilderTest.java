package com.yizhaoqi.smartpai.service.contextual;

import com.yizhaoqi.smartpai.model.DocumentVector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextWindowBuilderTest {

    @Test
    void marksTargetsAndKeepsNeighboursAsSurroundingText() {
        List<DocumentVector> window = List.of(
                chunk(1, "第三章 营收分析。", null),
                chunk(2, "营收比上季度增长 3%。", 4),
                chunk(3, "毛利率保持稳定。", 4));

        String message = ContextWindowBuilder.build("ACME-2023-Q2.pdf", window, Set.of(2), 1000);

        assertTrue(message.startsWith("文档标题：ACME-2023-Q2.pdf"));
        assertTrue(message.contains("第三章 营收分析。"), "前文交代分块所在章节");
        assertTrue(message.contains("【片段 #2，第 4 页】\n营收比上季度增长 3%。\n【片段 #2 结束】"));
        assertTrue(message.contains("毛利率保持稳定。"));
        assertFalse(message.contains("【片段 #1"), "邻居分块不要求生成说明");
        assertTrue(message.indexOf("第三章") < message.indexOf("【片段 #2") && message.indexOf("【片段 #2 结束】") < message.indexOf("毛利率"));
    }

    @Test
    void windowIsBoundedAndKeepsTargetsWhenDocumentIsLong() {
        String longBefore = "前".repeat(5000) + "紧挨目标的前文";
        String longAfter = "紧挨目标的后文" + "后".repeat(5000);
        List<DocumentVector> window = List.of(
                chunk(1, longBefore, null),
                chunk(2, "目标分块原文", null),
                chunk(3, longAfter, null));

        String message = ContextWindowBuilder.build("长文档", window, Set.of(2), 600);

        assertTrue(message.length() <= 600, "窗口长度不超过上限: " + message.length());
        assertTrue(message.contains("目标分块原文"));
        assertTrue(message.contains("紧挨目标的前文"), "前文保留结尾");
        assertTrue(message.contains("紧挨目标的后文"), "后文保留开头");
    }

    @Test
    void truncatesTargetsEvenlyWhenTheyAloneExceedBudget() {
        List<DocumentVector> window = List.of(
                chunk(1, "甲".repeat(2000), null),
                chunk(2, "乙".repeat(2000), null));

        String message = ContextWindowBuilder.build("t", window, Set.of(1, 2), 800);

        assertTrue(message.contains("【片段 #1】") && message.contains("【片段 #2 结束】"), "每个目标分块都保留标记");
        assertTrue(message.length() < 1000);
    }

    @Test
    void neighbourOverlapIsNotRepeated() {
        List<DocumentVector> window = List.of(
                chunk(1, "目标句。", null),
                chunk(2, "目标句。\n\n后续内容。", null));

        String message = ContextWindowBuilder.build("t", window, Set.of(1), 1000);

        assertTrue(message.contains("后续内容。"));
        assertTrue(message.indexOf("目标句。") == message.lastIndexOf("目标句。"), "邻居开头的重叠句去掉");
    }

    private static DocumentVector chunk(int id, String text, Integer page) {
        DocumentVector vector = new DocumentVector();
        vector.setFileMd5("md5");
        vector.setChunkId(id);
        vector.setTextContent(text);
        vector.setPageNumber(page);
        return vector;
    }
}
