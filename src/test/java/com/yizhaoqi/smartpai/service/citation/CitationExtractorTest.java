package com.yizhaoqi.smartpai.service.citation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationExtractorTest {

    @Test
    void extractsCitationBeforeAndAfterSentenceTerminator() {
        String answer = "RAG知识库支持 PDF 解析(来源#1: guide.pdf | 第3页)。向量模型默认是 text-embedding-v4。(来源#2: config.md)";

        List<CitationExtractor.CitedClaim> claims = CitationExtractor.extract(answer, 300);

        assertEquals(2, claims.size());
        assertEquals("RAG知识库支持 PDF 解析", claims.get(0).claim());
        assertEquals(List.of(1), claims.get(0).referenceNumbers());
        assertEquals("向量模型默认是 text-embedding-v4。", claims.get(1).claim());
        assertEquals(List.of(2), claims.get(1).referenceNumbers());
    }

    @Test
    void multipleSourcesInOneGroupAndAdjacentGroupsShareTheSentence() {
        String answer = "结论：支持断点续传（来源#1: a.pdf; 来源#3: b.pdf）(来源#4: c.pdf)。";

        List<CitationExtractor.CitedClaim> claims = CitationExtractor.extract(answer, 300);

        assertEquals(1, claims.size());
        assertEquals("结论：支持断点续传", claims.get(0).claim());
        assertEquals(List.of(1, 3, 4), claims.get(0).referenceNumbers());
    }

    @Test
    void secondCitationInSameSentenceOnlyCoversTextAfterPreviousCitation() {
        String answer = "- **上传**走分片(来源#1: a.pdf)，合并在磁盘上完成(来源#2: b.pdf)。";

        List<CitationExtractor.CitedClaim> claims = CitationExtractor.extract(answer, 300);

        assertEquals("上传走分片", claims.get(0).claim());
        assertEquals("合并在磁盘上完成", claims.get(1).claim());
    }

    @Test
    void newlineIsASentenceBoundary() {
        String answer = "第一点。\n1. 第二点说明(来源#5: x.pdf)";

        List<CitationExtractor.CitedClaim> claims = CitationExtractor.extract(answer, 300);

        assertEquals(1, claims.size());
        assertEquals("第二点说明", claims.get(0).claim());
        assertEquals(List.of(5), claims.get(0).referenceNumbers());
    }

    @Test
    void truncatesLongClaimsKeepingTheTail() {
        String answer = "很长".repeat(200) + "的结论(来源#1: a.pdf)";

        String claim = CitationExtractor.extract(answer, 10).get(0).claim();

        assertEquals(10, claim.length());
        assertTrue(claim.endsWith("的结论"));
    }

    @Test
    void answerWithoutCitationsYieldsNothing() {
        assertTrue(CitationExtractor.extract("暂无相关信息。", 300).isEmpty());
        assertTrue(CitationExtractor.extract(null, 300).isEmpty());
    }
}
