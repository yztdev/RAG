package com.yizhaoqi.smartpai.service.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkTextMergerTest {

    @Test
    void stripsSentenceOverlapCarriedFromPreviousChunk() {
        String first = "第一段讲端口。默认端口是 8080。";
        // 切分时下一块开头带上上一块结尾的句子作为重叠，重叠句之间没有空白
        String second = "默认端口是 8080。\n\n第二段讲超时。超时时间是 30 秒。";

        assertEquals("第一段讲端口。默认端口是 8080。\n\n第二段讲超时。超时时间是 30 秒。",
                ChunkTextMerger.merge(List.of(first, second)));
    }

    @Test
    void stripsOverlapEvenWhenWhitespaceDiffers() {
        String first = "A 句。\nB 句。";
        String second = "A 句。B 句。\n\nC 句。";

        assertEquals("C 句。", ChunkTextMerger.stripLeadingOverlap(first, second));
    }

    @Test
    void stripsLiteralOverlapWithoutParagraphSeparator() {
        String first = "the quick brown fox jumps over the lazy dog";
        String second = "over the lazy dog and runs away";

        assertEquals("and runs away", ChunkTextMerger.stripLeadingOverlap(first, second));
    }

    @Test
    void keepsTextWhenNoOverlapFound() {
        assertEquals("完全不同的内容。\n\n第二段。", ChunkTextMerger.stripLeadingOverlap("前一块。", "完全不同的内容。\n\n第二段。"));
        assertEquals("a\n\nb", ChunkTextMerger.merge(List.of("a", "", "b")));
    }
}
