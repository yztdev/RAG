package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.client.resilience.ModelCallGuard;
import com.yizhaoqi.smartpai.client.resilience.ModelCallRateLimiter;
import com.yizhaoqi.smartpai.client.resilience.ModelWebClientFactory;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.config.ChatDeadlineProperties;
import com.yizhaoqi.smartpai.config.ModelCallProperties;
import com.yizhaoqi.smartpai.service.citation.CitationVerificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.WebSocketSession;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 走真实的 processMessage → ReAct 循环 → LlmProviderRouter → 本地 HTTP 模型服务，检查请求级截止时间对工具轮的影响
 */
class ChatHandlerDeadlineTest {

    private HttpServer modelServer;
    private final AgentToolRegistry tools = mock(AgentToolRegistry.class);
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startModelServer() throws Exception {
        modelServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        modelServer.createContext("/", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            // 第一轮模型决定调用 search_knowledge，之后直接回答
            byte[] body = (requestBodies.size() == 1
                    ? "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                    + "\"function\":{\"name\":\"search_knowledge\",\"arguments\":\"{\\\"query\\\":\\\"青鸾\\\"}\"}}]},"
                    + "\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
                    : "data: {\"choices\":[{\"delta\":{\"content\":\"直接回答\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        modelServer.start();
    }

    @AfterEach
    void stopModelServer() {
        modelServer.stop(0);
    }

    @Test
    void notEnoughTimeForAnotherToolRoundAnswersDirectlyAfterFirstSearch() {
        ChatDeadlineProperties deadline = new ChatDeadlineProperties();
        deadline.setTotalSeconds(40);

        handler(deadline).processMessage("7", "青鸾计划的技术负责人是谁？", mock(WebSocketSession.class), "conv-1");

        assertEquals(2, requestBodies.size());
        // 第一轮照常带工具，保证至少检索一次
        assertTrue(requestBodies.get(0).contains("\"tools\""));
        // 剩余时间不够"一轮工具 + 最终回答预留"，第二轮不再给工具，直接回答
        assertFalse(requestBodies.get(1).contains("\"tools\""), "剩余时间不够时不应再给模型工具");
        assertTrue(requestBodies.get(1).contains("时间预算已用尽"));
        verify(tools).executeTool(eq("search_knowledge"), anyMap(), anyString(), any());
    }

    @Test
    void enoughTimeKeepsToolsInLaterRounds() {
        handler(new ChatDeadlineProperties()).processMessage("7", "青鸾计划的技术负责人是谁？", mock(WebSocketSession.class), "conv-1");

        assertEquals(2, requestBodies.size());
        assertTrue(requestBodies.get(0).contains("\"tools\""));
        assertTrue(requestBodies.get(1).contains("\"tools\""));
        assertFalse(requestBodies.get(1).contains("时间预算已用尽"));
    }

    @SuppressWarnings("unchecked")
    private ChatHandler handler(ChatDeadlineProperties deadline) {
        ModelCallProperties modelCallProperties = new ModelCallProperties();
        ModelProviderConfigService providerConfig = mock(ModelProviderConfigService.class);
        when(providerConfig.getLlmFailoverChain(any())).thenReturn(List.of(new ModelProviderConfigService.ActiveProviderView(
                "local", "local", ModelProviderConfigService.API_STYLE_OPENAI,
                "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1", "test-model", "test-key", null)));
        LlmProviderRouter router = new LlmProviderRouter(new AiProperties(), mock(RateLimitService.class),
                mock(UsageQuotaService.class), providerConfig, new ObjectMapper(), new ModelWebClientFactory(modelCallProperties),
                new ModelCallGuard(modelCallProperties, mock(ModelCallRateLimiter.class)), modelCallProperties);

        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(hash.entries(anyString())).thenReturn(Map.of());
        when(redis.opsForHash()).thenReturn((HashOperations) hash);

        ChatGenerationStateService state = mock(ChatGenerationStateService.class);
        when(state.createGeneration(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation ->
                new ChatGenerationStateService.GenerationSnapshot(invocation.getArgument(0), "7", "conv-1", "q",
                        ChatGenerationStateService.GenerationStatus.STREAMING, "", "", "", null, Map.of()));

        when(tools.executeTool(eq("search_knowledge"), anyMap(), anyString(), any())).thenReturn(
                new AgentToolRegistry.ToolExecutionResult("search_knowledge", true, "[1] 技术负责人是陆遥川", Map.of(), false));
        when(tools.getTools()).thenReturn(new ArrayList<>(List.of(new AgentToolRegistry.AgentTool(
                "search_knowledge", "检索知识库", Map.of("type", "object")))));

        // ReAct 循环在当前线程同步执行，便于断言
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        return new ChatHandler(redis, mock(HybridSearchService.class), router, mock(RateLimitService.class),
                mock(ConversationService.class), state, mock(ChatSessionRegistry.class), tools,
                mock(CitationVerificationService.class), new ObjectMapper(), executor, deadline);
    }
}
