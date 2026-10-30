package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.client.resilience.ModelCallGuard;
import com.yizhaoqi.smartpai.client.resilience.ModelCallRateLimiter;
import com.yizhaoqi.smartpai.client.resilience.ModelWebClientFactory;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.config.ModelCallProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用两个本地 HTTP 服务模拟主备供应商，走真实 WebClient：主供应商持续 503，重试耗尽后切到备用供应商
 */
class LlmProviderRouterFailoverTest {

    private HttpServer primary;
    private HttpServer backup;
    private final AtomicInteger primaryCalls = new AtomicInteger();
    private final AtomicReference<String> backupRequestBody = new AtomicReference<>();
    private LlmProviderRouter router;

    @BeforeEach
    void setUp() throws Exception {
        primary = server(exchange -> {
            primaryCalls.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        backup = server(exchange -> {
            backupRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean stream = backupRequestBody.get().contains("\"stream\":true");
            byte[] body = (stream
                    ? "data: {\"choices\":[{\"delta\":{\"content\":\"备用\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"回答\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n"
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"备用回答\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        ModelCallProperties properties = new ModelCallProperties();
        properties.getRetry().setInitialBackoffMs(1);
        properties.getRetry().setMaxBackoffMs(2);

        ModelProviderConfigService providerConfig = mock(ModelProviderConfigService.class);
        when(providerConfig.getLlmFailoverChain(any())).thenReturn(List.of(
                provider("deepseek", "deepseek-chat", primary),
                provider("qwen", "qwen-flash", backup)));

        router = new LlmProviderRouter(new AiProperties(), mock(RateLimitService.class), mock(UsageQuotaService.class),
                providerConfig, new ObjectMapper(), new ModelWebClientFactory(properties),
                new ModelCallGuard(properties, mock(ModelCallRateLimiter.class)), properties);
    }

    @AfterEach
    void tearDown() {
        primary.stop(0);
        backup.stop(0);
    }

    @Test
    void blockingCallFailsOverToBackupWithBackupModelName() {
        LlmProviderRouter.ReActTurn turn = router.completeReActTurn("1",
                List.of(Map.of("role", "user", "content", "你好")), List.of(), 100);

        assertEquals("备用回答", turn.content());
        // 首次调用 + 默认 2 次重试
        assertEquals(3, primaryCalls.get());
        assertTrue(backupRequestBody.get().contains("\"model\":\"qwen-flash\""));
    }

    @Test
    void streamFailsOverBeforeAnyContent() throws Exception {
        StringBuilder content = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        router.streamReActTurn("1", List.of(Map.of("role", "user", "content", "你好")), List.of(), 100,
                content::append,
                failure -> {
                    error.set(failure);
                    done.countDown();
                },
                turn -> done.countDown());

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNull(error.get());
        assertEquals("备用回答", content.toString());
        assertEquals(3, primaryCalls.get());
    }

    private static ModelProviderConfigService.ActiveProviderView provider(String code, String model, HttpServer server) {
        return new ModelProviderConfigService.ActiveProviderView(code, code, ModelProviderConfigService.API_STYLE_OPENAI,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", model, "test-key", null);
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }
}
