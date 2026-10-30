package com.yizhaoqi.smartpai.service.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 给升级前写入、还没有 fileName 字段的分块补上文件名，让旧文档也能按文件名参与关键词打分。
 * <p>
 * 只处理缺这个字段的文档，可重复执行；在后台线程里跑，不阻塞启动。
 * 需在 EsIndexInitializer（追加 mapping 字段）之后执行
 */
@Component
@Order(3)
public class FileNameIndexBackfill implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(FileNameIndexBackfill.class);
    private static final int MAX_FILES = 10_000;

    private final ElasticsearchClient esClient;
    private final FileUploadRepository fileUploadRepository;

    public FileNameIndexBackfill(ElasticsearchClient esClient, FileUploadRepository fileUploadRepository) {
        this.esClient = esClient;
        this.fileUploadRepository = fileUploadRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread worker = new Thread(this::backfill, "es-filename-backfill");
        worker.setDaemon(true);
        worker.start();
    }

    void backfill() {
        try {
            List<String> md5s = filesMissingName();
            if (md5s.isEmpty()) {
                return;
            }
            long updated = 0;
            int skipped = 0;
            for (String md5 : md5s) {
                String name = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(md5)
                        .map(FileUpload::getFileName)
                        .orElse(null);
                if (name == null || name.isBlank()) {
                    skipped++;
                    continue;
                }
                updated += esClient.updateByQuery(u -> u
                        .index(KnowledgeIndexNames.ALIAS)
                        .conflicts(Conflicts.Proceed)
                        .query(q -> q.bool(b -> b
                                .filter(f -> f.term(t -> t.field("fileMd5").value(md5)))
                                .mustNot(m -> m.exists(e -> e.field("fileName")))))
                        .script(s -> s.inline(i -> i
                                .lang("painless")
                                .source("ctx._source.fileName = params.name")
                                .params(Map.of("name", JsonData.of(name)))))
                ).updated();
            }
            logger.info("已为旧分块补写文件名: files={}, chunks={}, 找不到上传记录跳过={}", md5s.size() - skipped, updated, skipped);
        } catch (Exception e) {
            logger.warn("补写分块文件名失败，旧文档暂不参与文件名打分，下次启动会重试: {}", e.getMessage());
        }
    }

    private List<String> filesMissingName() throws Exception {
        SearchResponse<Void> response = esClient.search(s -> s
                .index(KnowledgeIndexNames.ALIAS)
                .size(0)
                .query(q -> q.bool(b -> b.mustNot(m -> m.exists(e -> e.field("fileName")))))
                .aggregations("files", a -> a.terms(t -> t.field("fileMd5").size(MAX_FILES))), Void.class);
        return response.aggregations().get("files").sterms().buckets().array().stream()
                .map(StringTermsBucket::key)
                .map(key -> key.stringValue())
                .toList();
    }
}
