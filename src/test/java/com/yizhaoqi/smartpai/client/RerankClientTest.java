package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.HybridSearchProperties;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RerankClientTest {

    private final ModelProviderConfigService providerConfig = mock(ModelProviderConfigService.class);
    private final HybridSearchProperties properties = new HybridSearchProperties();
    private final RerankClient client = new RerankClient(properties, providerConfig, new ObjectMapper());

    @Test
    void parsesDashScopeResponseSortedByScoreAndDropsInvalidIndexes() {
        String response = """
                {"output":{"results":[
                  {"index":1,"relevance_score":0.31},
                  {"index":0,"relevance_score":0.92},
                  {"index":7,"relevance_score":0.99},
                  {"index":2}
                ]},"usage":{"total_tokens":120},"request_id":"r1"}
                """;

        List<RerankClient.RerankHit> hits = client.parseResponse(response, 3);

        assertEquals(List.of(new RerankClient.RerankHit(0, 0.92), new RerankClient.RerankHit(1, 0.31)), hits);
    }

    @Test
    void failsOnUnexpectedResponse() {
        assertThrows(IllegalStateException.class, () -> client.parseResponse("{\"code\":\"InvalidApiKey\"}", 2));
        assertThrows(IllegalStateException.class, () -> client.parseResponse("not json", 2));
    }

    @Test
    void requiresApiKeyWhenEmbeddingProviderIsNotDashScope() {
        when(providerConfig.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING)).thenReturn(
                new ModelProviderConfigService.ActiveProviderView("zhipu", "智谱", "openai-compatible", "https://x", "embedding-3", "k", 2048));

        assertThrows(IllegalStateException.class, () -> client.rerank("q", List.of("doc")));
    }

    @Test
    void emptyCandidatesSkipTheCall() {
        assertEquals(List.of(), client.rerank("q", List.of()));
    }
}
