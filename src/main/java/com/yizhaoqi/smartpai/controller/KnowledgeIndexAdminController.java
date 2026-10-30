package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.service.index.EmbeddingReindexService;
import com.yizhaoqi.smartpai.service.index.KnowledgeIndexManager;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import com.yizhaoqi.smartpai.utils.LogUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库索引管理（仅管理员，/api/v1/admin/** 由 SecurityConfig 限制为 ADMIN 角色）：
 * 查看版本索引、触发 embedding 模型升级重建、查看进度、回滚别名。
 */
@RestController
@RequestMapping("/api/v1/admin/knowledge-index")
public class KnowledgeIndexAdminController {

    private final EmbeddingReindexService reindexService;
    private final KnowledgeIndexManager indexManager;
    private final JwtUtils jwtUtils;

    public KnowledgeIndexAdminController(EmbeddingReindexService reindexService,
                                         KnowledgeIndexManager indexManager,
                                         JwtUtils jwtUtils) {
        this.reindexService = reindexService;
        this.indexManager = indexManager;
        this.jwtUtils = jwtUtils;
    }

    @GetMapping
    public ResponseEntity<?> overview() {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("liveIndex", indexManager.currentAliasTarget().orElse(null));
            data.put("legacyConcreteIndex", indexManager.hasLegacyConcreteIndex());
            data.put("indices", indexManager.listIndices());
            data.put("rebuild", reindexService.getStatus().orElse(null));
            return ok("获取知识库索引信息成功", data);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "获取知识库索引信息失败: " + e.getMessage());
        }
    }

    @PostMapping("/rebuild")
    public ResponseEntity<?> rebuild(@RequestHeader("Authorization") String token, @RequestBody RebuildRequest request) {
        String operator = operator(token);
        if (request == null || request.targetProvider() == null || request.targetProvider().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "targetProvider 不能为空");
        }
        try {
            var status = reindexService.startRebuild(request.targetProvider().trim(), Boolean.TRUE.equals(request.fresh()), operator);
            LogUtils.logUserOperation(operator, "EMBEDDING_REINDEX_START", status.targetIndex(), "SUBMITTED");
            return ok("embedding 重建任务已提交", status);
        } catch (CustomException e) {
            return error(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            LogUtils.logBusinessError("EMBEDDING_REINDEX_START", operator, "提交重建任务失败", e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "提交重建任务失败: " + e.getMessage());
        }
    }

    @GetMapping("/rebuild")
    public ResponseEntity<?> rebuildStatus() {
        return ok("获取重建进度成功", reindexService.getStatus().orElse(null));
    }

    @PostMapping("/rollback")
    public ResponseEntity<?> rollback(@RequestHeader("Authorization") String token, @RequestBody RollbackRequest request) {
        String operator = operator(token);
        if (request == null || request.index() == null || request.index().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "index 不能为空");
        }
        try {
            reindexService.rollback(request.index().trim(), operator);
            LogUtils.logUserOperation(operator, "EMBEDDING_REINDEX_ROLLBACK", request.index(), "SUCCESS");
            return ok("已回滚到索引 " + request.index(), null);
        } catch (CustomException e) {
            return error(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            LogUtils.logBusinessError("EMBEDDING_REINDEX_ROLLBACK", operator, "回滚失败", e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "回滚失败: " + e.getMessage());
        }
    }

    private String operator(String token) {
        return jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
    }

    private ResponseEntity<?> ok(String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("message", message);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("code", status.value(), "message", message));
    }

    public record RebuildRequest(String targetProvider, Boolean fresh) {
    }

    public record RollbackRequest(String index) {
    }
}
