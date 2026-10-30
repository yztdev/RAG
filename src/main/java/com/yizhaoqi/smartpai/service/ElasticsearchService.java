package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.service.index.KnowledgeIndexNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

// Elasticsearch操作封装服务
@Service
public class ElasticsearchService {

    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchService.class);

    @Autowired
    private ElasticsearchClient esClient;

    /**
     * 批量索引文档到知识库别名（别名指向当前线上的物理索引）
     *
     * @param documents 文档列表，每个文档都将被索引到Elasticsearch中
     */
    public void bulkIndex(List<EsDocument> documents) {
        bulkIndex(KnowledgeIndexNames.ALIAS, documents);
    }

    /**
     * 批量索引文档到指定索引（embedding 重建任务写入新版本索引时使用）
     */
    public void bulkIndex(String index, List<EsDocument> documents) {
        try {
            logger.info("开始批量索引文档到Elasticsearch，index: {}, 文档数量: {}", index, documents.size());

            List<BulkOperation> bulkOperations = documents.stream()
                    .map(doc -> BulkOperation.of(op -> op.index(idx -> idx
                            .index(index)
                            .id(doc.getId())
                            .document(doc)
                    )))
                    .toList();

            BulkRequest request = BulkRequest.of(b -> b.operations(bulkOperations));
            BulkResponse response = esClient.bulk(request);

            if (response.errors()) {
                logger.error("批量索引过程中发生错误:");
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        logger.error("文档索引失败 - ID: {}, 错误: {}", item.id(), item.error().reason());
                    }
                }
                throw new RuntimeException("批量索引部分失败，请检查日志");
            }
            logger.info("批量索引成功完成，index: {}, 文档数量: {}", index, documents.size());
        } catch (Exception e) {
            logger.error("批量索引失败，index: {}, 文档数量: {}", index, documents.size(), e);
            throw new RuntimeException("批量索引失败", e);
        }
    }

    /**
     * 根据file_md5删除文档。
     * 同时清理所有版本索引（线上、重建中、保留用于回滚的旧索引），避免回滚后已删除的文档"复活"。
     *
     * @param fileMd5 文件指纹
     */
    public void deleteByFileMd5(String fileMd5) {
        deleteByFileMd5(KnowledgeIndexNames.ALL_INDICES_PATTERN, fileMd5);
    }

    public void deleteByFileMd5(String index, String fileMd5) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(index)
                    .allowNoIndices(true)
                    .ignoreUnavailable(true)
                    .query(q -> q.term(t -> t.field("fileMd5").value(fileMd5)))
            );
            esClient.deleteByQuery(request);
        } catch (Exception e) {
            throw new RuntimeException("删除文档失败", e);
        }
    }

    public long countByFileMd5(String fileMd5) {
        return countByFileMd5(KnowledgeIndexNames.ALIAS, fileMd5);
    }

    public long countByFileMd5(String index, String fileMd5) {
        try {
            CountResponse response = esClient.count(c -> c
                    .index(index)
                    .query(q -> q.term(t -> t.field("fileMd5").value(fileMd5)))
            );
            return response.count();
        } catch (Exception e) {
            throw new RuntimeException("统计文档失败", e);
        }
    }

    /**
     * 统计指定索引中某文件由指定 embedding 模型版本生成的分块数，用于重建任务的幂等续建判断
     */
    public long countByFileMd5AndModelVersion(String index, String fileMd5, String modelVersion) {
        try {
            CountResponse response = esClient.count(c -> c
                    .index(index)
                    .query(q -> q.bool(b -> b
                            .filter(f -> f.term(t -> t.field("fileMd5").value(fileMd5)))
                            .filter(f -> f.term(t -> t.field("modelVersion").value(modelVersion)))))
            );
            return response.count();
        } catch (Exception e) {
            throw new RuntimeException("统计文档失败", e);
        }
    }
}
