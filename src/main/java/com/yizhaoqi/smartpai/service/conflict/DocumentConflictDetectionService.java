package com.yizhaoqi.smartpai.service.conflict;

import com.yizhaoqi.smartpai.config.DocumentConflictProperties;
import com.yizhaoqi.smartpai.model.DocumentConflict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 文档冲突检测入口：订阅向量化完成事件，在独立线程池里扫描新文件。
 * 检测失败只记日志，不影响文件入库；检测结果只写冲突记录，不删除、不覆盖任何文档。
 */
@Service
public class DocumentConflictDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentConflictDetectionService.class);

    private final DocumentConflictProperties properties;
    private final ConflictCandidateFinder candidateFinder;
    private final ConflictLlmConfirmer llmConfirmer;
    private final DocumentConflictService conflictService;
    private final Executor executor;
    private final Set<String> scanning = ConcurrentHashMap.newKeySet();

    public DocumentConflictDetectionService(DocumentConflictProperties properties,
                                            ConflictCandidateFinder candidateFinder,
                                            ConflictLlmConfirmer llmConfirmer,
                                            DocumentConflictService conflictService,
                                            @Qualifier("conflictDetectionExecutor") Executor executor) {
        this.properties = properties;
        this.candidateFinder = candidateFinder;
        this.llmConfirmer = llmConfirmer;
        this.conflictService = conflictService;
        this.executor = executor;
    }

    /**
     * 事务提交后才触发（重建单个文档在事务里完成，分块要提交后才读得到）；没有事务时立即触发。
     * 这里只负责把任务丢进线程池，任何异常都不能抛回向量化流程。
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void onDocumentVectorized(DocumentVectorizedEvent event) {
        if (!properties.isEnabled() || event == null || event.fileMd5() == null) {
            return;
        }
        try {
            executor.execute(() -> scanQuietly(event.fileMd5()));
        } catch (Exception e) {
            logger.warn("冲突检测任务提交失败，跳过: fileMd5={}, error={}", event.fileMd5(), e.getMessage());
        }
    }

    void scanQuietly(String fileMd5) {
        if (!scanning.add(fileMd5)) {
            logger.info("文件正在做冲突检测，跳过重复触发: fileMd5={}", fileMd5);
            return;
        }
        try {
            scan(fileMd5);
        } catch (Exception e) {
            logger.warn("冲突检测失败，不影响文件入库: fileMd5={}", fileMd5, e);
        } finally {
            scanning.remove(fileMd5);
        }
    }

    public List<DocumentConflict> scan(String fileMd5) throws Exception {
        long start = System.currentTimeMillis();
        List<ConflictCandidate> candidates = candidateFinder.findCandidates(fileMd5);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<ConflictCandidate> confirmed = llmConfirmer.confirm(candidates);
        List<DocumentConflict> saved = conflictService.recordCandidates(confirmed);
        logger.info("冲突检测完成: fileMd5={}, 规则候选={}, 确认={}, 新增记录={}, 耗时={}ms",
                fileMd5, candidates.size(), confirmed.size(), saved.size(), System.currentTimeMillis() - start);
        return saved;
    }
}
