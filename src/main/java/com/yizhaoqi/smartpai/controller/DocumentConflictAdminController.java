package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.DocumentConflict;
import com.yizhaoqi.smartpai.service.conflict.DocumentConflictService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import com.yizhaoqi.smartpai.utils.LogUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 文档冲突管理（仅管理员，/api/v1/admin/** 由 SecurityConfig 限制为 ADMIN 角色）：
 * 查看疑似冲突、人工决定保留哪一篇。系统不会自动删除任何文档。
 */
@RestController
@RequestMapping("/api/v1/admin/document-conflicts")
public class DocumentConflictAdminController {

    private final DocumentConflictService conflictService;
    private final JwtUtils jwtUtils;

    public DocumentConflictAdminController(DocumentConflictService conflictService, JwtUtils jwtUtils) {
        this.conflictService = conflictService;
        this.jwtUtils = jwtUtils;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        try {
            DocumentConflict.Status filter = status == null || status.isBlank() ? null : parseStatus(status);
            return ok("获取文档冲突列表成功", conflictService.list(filter, page, size));
        } catch (CustomException e) {
            return error(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "获取文档冲突列表失败: " + e.getMessage());
        }
    }

    /**
     * action: KEEP_NEW（删除旧文档）/ KEEP_EXISTING（删除新文档）/ IGNORED（不是冲突，只改状态）
     */
    @PostMapping("/{id}/resolve")
    public ResponseEntity<?> resolve(@RequestHeader("Authorization") String token,
                                     @PathVariable Long id,
                                     @RequestBody ResolveRequest request) {
        String operator = operator(token);
        if (request == null || request.action() == null || request.action().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "action 不能为空");
        }
        try {
            DocumentConflict.Status action = parseStatus(request.action());
            DocumentConflict resolved = conflictService.resolve(id, action, operator, request.note());
            LogUtils.logUserOperation(operator, "DOCUMENT_CONFLICT_RESOLVE", String.valueOf(id), action.name());
            return ok("冲突已处理", resolved);
        } catch (CustomException e) {
            return error(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            LogUtils.logBusinessError("DOCUMENT_CONFLICT_RESOLVE", operator, "处理文档冲突失败: id=%s", e, id);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "处理文档冲突失败: " + e.getMessage());
        }
    }

    private static DocumentConflict.Status parseStatus(String value) {
        try {
            return DocumentConflict.Status.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CustomException("未知的状态: " + value, HttpStatus.BAD_REQUEST);
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

    public record ResolveRequest(String action, String note) {
    }
}
