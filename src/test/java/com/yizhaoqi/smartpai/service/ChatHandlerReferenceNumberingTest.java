package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ChatHandlerReferenceNumberingTest {
    @Test
    void multipleSearchesKeepEarlierReferencesAndUseDistinctNumbers() {
        ChatGenerationStateService state = mock(ChatGenerationStateService.class);
        ChatHandler handler = new ChatHandler(null, null, null, null, null, state,
                null, null, null, null, null, null);
        SearchResult first = new SearchResult("first-md5", 1, "first text", 0.9, "first.txt");
        SearchResult second = new SearchResult("second-md5", 2, "second text", 0.8, "second.txt");

        String firstContent = handler.appendReferencesFromSearchTool("generation", "question",
                result(first));
        String secondContent = handler.appendReferencesFromSearchTool("generation", "question",
                result(second));

        assertTrue(firstContent.contains("[1] first.txt"));
        assertTrue(secondContent.contains("[2] second.txt"));
        assertEquals("first-md5", handler.getReferenceDetail("generation", 1).fileMd5());
        assertEquals("second-md5", handler.getReferenceDetail("generation", 2).fileMd5());
        verify(state, times(2)).updateReferenceMappings(eq("generation"), any());
    }

    @Test
    void sharedParentBlockIsSentOnceButEachReferenceKeepsItsSmallChunk() {
        ChatGenerationStateService state = mock(ChatGenerationStateService.class);
        ChatHandler handler = new ChatHandler(null, null, null, null, null, state,
                null, null, null, null, null, null);
        String parent = "端口配置为 8080。\n\n修改端口后需要重启服务。";
        SearchResult first = new SearchResult("md5", 3, "端口配置为 8080。", 0.9, "7", "org", false, "manual.pdf",
                2, "端口配置为 8080。", "HYBRID", null).withParentContext(parent, "md5#3-4");
        SearchResult second = new SearchResult("md5", 4, "修改端口后需要重启服务。", 0.8, "7", "org", false, "manual.pdf",
                3, "修改端口后需要重启服务。", "HYBRID", null).withParentContext(parent, "md5#3-4");

        String content = handler.appendReferencesFromSearchTool("generation", "端口怎么改",
                new AgentToolRegistry.ToolExecutionResult("search_knowledge", true, "",
                        Map.of("results", List.of(first, second))));

        assertTrue(content.contains("[1] manual.pdf (fileMd5=md5, chunkId=3, page=2, score=0.9000)\n上下文（命中分块及前后相邻内容）：\n" + parent),
                "第一个命中给出父块");
        assertTrue(content.contains("[2] manual.pdf"));
        assertTrue(content.contains("（该分块所在的上下文已在 [1] 中给出）"), "共用的父块只发一次");
        assertEquals(1, content.split("上下文（命中分块及前后相邻内容）", -1).length - 1);
        assertEquals("端口配置为 8080。", handler.getReferenceDetail("generation", 1).matchedChunkText(),
                "引用详情仍是命中的小分块");
        assertEquals(3, handler.getReferenceDetail("generation", 2).pageNumber());
        assertEquals(4, handler.getReferenceDetail("generation", 2).chunkId());
    }

    private static AgentToolRegistry.ToolExecutionResult result(SearchResult result) {
        return new AgentToolRegistry.ToolExecutionResult("search_knowledge", true,
                AgentToolRegistry.formatSearchResults(List.of(result)), Map.of("results", List.of(result)));
    }
}
