package com.yizhaoqi.smartpai.client.resilience;

import com.yizhaoqi.smartpai.config.ModelCallProperties;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import io.netty.channel.ChannelOption;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按供应商共享 WebClient：每个 (scope, provider) 一个连接池，配置连接超时和读超时。
 * 管理员修改了地址或 API Key 时按指纹重建客户端，连接池继续复用
 */
@Component
public class ModelWebClientFactory {

    private final ModelCallProperties properties;
    private final Map<String, ConnectionProvider> connectionProviders = new ConcurrentHashMap<>();
    private final Map<String, CachedClient> clients = new ConcurrentHashMap<>();

    public ModelWebClientFactory(ModelCallProperties properties) {
        this.properties = properties;
    }

    public WebClient clientFor(String scope, ModelProviderConfigService.ActiveProviderView provider) {
        String name = scope + ":" + provider.provider();
        String baseUrl = ModelProviderConfigService.normalizeOpenAiCompatibleBaseUrl(provider.apiBaseUrl());
        String fingerprint = fingerprint(baseUrl, provider.apiKey());
        return clients.compute(name, (key, cached) -> cached != null && cached.fingerprint().equals(fingerprint)
                ? cached
                : new CachedClient(fingerprint, build(name, baseUrl, provider.apiKey()))).client();
    }

    private WebClient build(String name, String baseUrl, String apiKey) {
        ModelCallProperties.Http http = properties.getHttp();
        ConnectionProvider pool = connectionProviders.computeIfAbsent(name, key -> ConnectionProvider.builder("model-" + key)
                .maxConnections(Math.max(http.getMaxConnections(), 1))
                .pendingAcquireTimeout(Duration.ofSeconds(Math.max(http.getPendingAcquireTimeoutSeconds(), 1L)))
                .maxIdleTime(Duration.ofSeconds(Math.max(http.getMaxIdleSeconds(), 1L)))
                .evictInBackground(Duration.ofSeconds(Math.max(http.getMaxIdleSeconds(), 1L)))
                .build());
        HttpClient httpClient = HttpClient.create(pool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.max(http.getConnectTimeoutMs(), 1))
                .responseTimeout(Duration.ofSeconds(Math.max(http.getReadTimeoutSeconds(), 1L)));

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(Math.max(http.getMaxInMemorySizeMb(), 1) * 1024 * 1024));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        return builder.build();
    }

    @PreDestroy
    public void shutdown() {
        connectionProviders.values().forEach(ConnectionProvider::dispose);
    }

    private static String fingerprint(String baseUrl, String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(baseUrl.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update((apiKey == null ? "" : apiKey).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("计算模型客户端指纹失败", e);
        }
    }

    private record CachedClient(String fingerprint, WebClient client) {
    }
}
