package com.yizhaoqi.smartpai.config;

import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import com.yizhaoqi.smartpai.service.index.KnowledgeIndexManager;
import com.yizhaoqi.smartpai.service.index.KnowledgeIndexNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.apache.http.ConnectionClosedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

// 确保在 BootstrapKnowledgeInitializer 之前进行初始化
@Order(2)
@Component
@ConditionalOnProperty(name = "elasticsearch.init.enabled", havingValue = "true", matchIfMissing = true)
public class EsIndexInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(EsIndexInitializer.class);

    private static final int DEFAULT_VECTOR_DIMS = 2048;

    @Autowired
    private KnowledgeIndexManager knowledgeIndexManager;

    @Autowired
    private ModelProviderConfigService modelProviderConfigService;

    @Autowired
    private ContextualRetrievalProperties contextualRetrievalProperties;

    @Value("${elasticsearch.host}")
    private String host;

    @Value("${elasticsearch.port}")
    private int port;

    @Value("${elasticsearch.scheme:https}")
    private String scheme;

    @Value("${elasticsearch.username:elastic}")
    private String username;

    @Override
    public void run(String... args) throws Exception {
        try {
            logger.info("正在初始化知识库索引 '{}'... endpoint={}://{}:{}, username={}", KnowledgeIndexNames.ALIAS,
                    scheme, host, port, maskUsername(username));
            initializeIndex();
        } catch (Exception exception) {
            // 特别处理连接关闭异常，尝试重新连接
            if (exception instanceof ConnectionClosedException || (exception.getCause() != null && exception.getCause() instanceof ConnectionClosedException)) {
                logger.error("Elasticsearch连接已关闭，等待5秒后重试...");
                try {
                    Thread.sleep(5000); // 等待5秒后重试
                    // 重新尝试初始化索引
                    initializeIndex();
                } catch (Exception retryException) {
                    String diagnostic = buildDiagnosticMessage(retryException);
                    logger.error("重试初始化索引失败。{}", diagnostic, retryException);
                    throw new RuntimeException("初始化索引失败，重试也未能成功。" + diagnostic, retryException);
                }
            } else {
                String diagnostic = buildDiagnosticMessage(exception);
                logger.error("初始化索引失败。{}", diagnostic, exception);
                throw new RuntimeException("初始化索引失败。" + diagnostic, exception);
            }
        }
    }

    /**
     * 初始化索引的核心逻辑：业务读写别名 knowledge_base，别名指向按 embedding 模型版本命名的物理索引
     */
    private void initializeIndex() throws Exception {
        Optional<String> aliasTarget = knowledgeIndexManager.currentAliasTarget();
        if (aliasTarget.isPresent()) {
            logger.info("知识库别名 '{}' 已存在，指向索引 {}", KnowledgeIndexNames.ALIAS, aliasTarget.get());
            syncTemplateFields(aliasTarget.get());
            return;
        }
        if (knowledgeIndexManager.hasLegacyConcreteIndex()) {
            // 兼容旧版本：继续直接使用同名物理索引，第一次 embedding 重建切换时会备份并迁移为别名
            logger.info("检测到旧版物理索引 '{}'，继续使用；下次 embedding 重建时会迁移为别名", KnowledgeIndexNames.ALIAS);
            syncTemplateFields(KnowledgeIndexNames.ALIAS);
            return;
        }
        createIndex();
    }

    /**
     * 给已有索引补上模板里新增的字段（如上下文检索的 chunkContext）。
     * 上下文检索关闭时写入的 chunkContext 都是 null，不会触发动态映射，补字段失败只告警；
     * 开启时字段必须先用 IK 分词器建好，否则直接启动失败，避免 ES 用 standard 分词器自动建字段。
     */
    private void syncTemplateFields(String index) throws Exception {
        try {
            knowledgeIndexManager.ensureTemplateFields(index);
        } catch (Exception e) {
            if (contextualRetrievalProperties != null && contextualRetrievalProperties.isEnabled()) {
                throw e;
            }
            logger.warn("为索引 {} 追加 mapping 字段失败，上下文检索未开启，暂不影响使用: {}", index, e.getMessage());
        }
    }

    /**
     * 全新环境：按当前激活的 embedding 模型创建版本索引并挂上别名
     */
    private void createIndex() throws Exception {
        ModelProviderConfigService.ActiveProviderView provider =
                modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
        String modelVersion = EmbeddingClient.modelVersionOf(provider);
        String index = KnowledgeIndexNames.versionedIndexName(modelVersion);
        int dims = provider.dimension() != null ? provider.dimension() : DEFAULT_VECTOR_DIMS;
        knowledgeIndexManager.createIndex(index, dims, Map.of(
                KnowledgeIndexManager.META_PROVIDER, provider.provider(),
                KnowledgeIndexManager.META_MODEL_VERSION, modelVersion,
                KnowledgeIndexManager.META_CREATED_AT, Instant.now().toString()
        ), true);
        logger.info("索引 '{}' 已创建，并挂载别名 '{}'", index, KnowledgeIndexNames.ALIAS);
    }

    private String buildDiagnosticMessage(Exception exception) {
        Throwable rootCause = getRootCause(exception);
        String rootMessage = safeMessage(rootCause);
        String normalizedMessage = rootMessage.toLowerCase(Locale.ROOT);
        String endpoint = scheme + "://" + host + ":" + port;
        List<String> hints = new ArrayList<>();

        hints.add("ES地址=" + endpoint);

        if (isConnectionProblem(rootCause, normalizedMessage)) {
            hints.add("当前看起来是连接失败，请先确认 Elasticsearch 已启动，并且 " + endpoint + " 能访问");
        }

        if (isSslMismatch(normalizedMessage)) {
            hints.add("当前更像是 HTTP/HTTPS 协议不匹配，请核对 ELASTICSEARCH_SCHEME 与实际 ES 配置");
        }

        if (isAuthenticationProblem(normalizedMessage)) {
            hints.add("当前更像是账号或密码不正确，请核对 ELASTICSEARCH_USERNAME / ELASTICSEARCH_PASSWORD");
        }

        if (normalizedMessage.contains("ik_max_word") || normalizedMessage.contains("ik_smart")) {
            hints.add("当前索引 mapping 依赖 IK 分词器，请确认 ES 已安装 analysis-ik 插件");
        }

        if (normalizedMessage.contains("dense_vector") && normalizedMessage.contains("dims")) {
            hints.add("当前更像是向量字段维度不匹配，请确认 embedding.dimension 与索引 mapping 中的 dims 一致");
        }

        if (hints.size() == 1) {
            hints.add("请查看根因异常后再排查 ES 协议、端口、认证和 mapping 配置");
        }

        return " 根因类型=" + rootCause.getClass().getSimpleName()
                + "，根因信息=" + rootMessage
                + "。排查建议：" + String.join("；", hints);
    }

    private Throwable getRootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private boolean isConnectionProblem(Throwable rootCause, String normalizedMessage) {
        return rootCause instanceof ConnectException
                || normalizedMessage.contains("connection refused")
                || normalizedMessage.contains("connect timed out")
                || normalizedMessage.contains("connection timed out")
                || normalizedMessage.contains("failed to connect")
                || normalizedMessage.contains("no such host")
                || normalizedMessage.contains("unknownhost")
                || normalizedMessage.contains("no reachable node")
                || normalizedMessage.contains("connection reset");
    }

    private boolean isSslMismatch(String normalizedMessage) {
        return normalizedMessage.contains("pkix")
                || normalizedMessage.contains("ssl")
                || normalizedMessage.contains("tls")
                || normalizedMessage.contains("handshake")
                || normalizedMessage.contains("plaintext connection")
                || normalizedMessage.contains("unrecognized ssl message");
    }

    private boolean isAuthenticationProblem(String normalizedMessage) {
        return normalizedMessage.contains("security_exception")
                || normalizedMessage.contains("authentication")
                || normalizedMessage.contains("unauthorized")
                || normalizedMessage.contains("status line [http/1.1 401")
                || normalizedMessage.contains("status line [http/1.1 403");
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return (message == null || message.isBlank()) ? "<无异常消息>" : message;
    }

    private String maskUsername(String rawUsername) {
        if (rawUsername == null || rawUsername.isBlank()) {
            return "<empty>";
        }
        if (rawUsername.length() <= 2) {
            return rawUsername.charAt(0) + "*";
        }
        return rawUsername.charAt(0) + "***" + rawUsername.charAt(rawUsername.length() - 1);
    }
}
