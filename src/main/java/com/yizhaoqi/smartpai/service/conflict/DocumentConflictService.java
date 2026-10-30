package com.yizhaoqi.smartpai.service.conflict;

import com.yizhaoqi.smartpai.config.DocumentConflictProperties;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.DocumentConflict;
import com.yizhaoqi.smartpai.model.DocumentConflict.Status;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.DocumentConflictRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 疑似冲突的落库、查询和人工处理。
 * 处理必须由管理员显式发起：KEEP_NEW 删除旧文档，KEEP_EXISTING 删除新文档，IGNORED 只改状态。
 */
@Service
public class DocumentConflictService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentConflictService.class);
    /** 同一分块对已有待处理记录，或管理员已判定"不是冲突"时，不再重复记录 */
    private static final Set<Status> DEDUP_STATUSES = EnumSet.of(Status.PENDING, Status.IGNORED);
    private static final Set<Status> RESOLVE_ACTIONS = EnumSet.of(Status.KEEP_NEW, Status.KEEP_EXISTING, Status.IGNORED);
    private static final int MAX_VALUES_CHARS = 480;
    private static final int MAX_NOTE_CHARS = 480;

    private final DocumentConflictRepository conflictRepository;
    private final FileUploadRepository fileUploadRepository;
    private final DocumentService documentService;
    private final DocumentConflictProperties properties;

    public DocumentConflictService(DocumentConflictRepository conflictRepository,
                                   FileUploadRepository fileUploadRepository,
                                   DocumentService documentService,
                                   DocumentConflictProperties properties) {
        this.conflictRepository = conflictRepository;
        this.fileUploadRepository = fileUploadRepository;
        this.documentService = documentService;
        this.properties = properties;
    }

    /**
     * 落库并去重：同一文件重复处理（重试、重建）不会产生重复的 PENDING 记录
     */
    public List<DocumentConflict> recordCandidates(List<ConflictCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, String> fileNames = lookupFileNames(candidates);
        List<DocumentConflict> saved = new ArrayList<>();
        for (ConflictCandidate candidate : candidates) {
            String pairKey = candidate.pairKey();
            if (conflictRepository.existsByPairKeyAndStatusIn(pairKey, DEDUP_STATUSES)) {
                logger.debug("疑似冲突已存在，跳过: pairKey={}", pairKey);
                continue;
            }
            saved.add(conflictRepository.save(toEntity(candidate, pairKey, fileNames)));
        }
        if (!saved.isEmpty()) {
            logger.warn("发现疑似文档冲突 {} 条，等待管理员确认: newFile={}",
                    saved.size(), candidates.get(0).newChunk().fileMd5());
        }
        return saved;
    }

    public Map<String, Object> list(Status status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), Math.min(Math.max(1, size), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<DocumentConflict> result = status == null
                ? conflictRepository.findAll(pageable)
                : conflictRepository.findByStatus(status, pageable);
        return Map.of(
                "records", result.getContent(),
                "total", result.getTotalElements(),
                "pages", result.getTotalPages(),
                "current", page,
                "size", size
        );
    }

    /**
     * 管理员处理一条疑似冲突。先删文档再改状态：删除失败时冲突保持 PENDING，可以重试。
     */
    public synchronized DocumentConflict resolve(Long id, Status action, String operator, String note) {
        if (action == null || !RESOLVE_ACTIONS.contains(action)) {
            throw new CustomException("action 只能是 KEEP_NEW / KEEP_EXISTING / IGNORED", HttpStatus.BAD_REQUEST);
        }
        DocumentConflict conflict = conflictRepository.findById(id)
                .orElseThrow(() -> new CustomException("冲突记录不存在: " + id, HttpStatus.NOT_FOUND));
        if (conflict.getStatus() != Status.PENDING) {
            throw new CustomException("冲突已处理，当前状态: " + conflict.getStatus(), HttpStatus.CONFLICT);
        }

        String deletedMd5 = switch (action) {
            case KEEP_NEW -> conflict.getExistingFileMd5();
            case KEEP_EXISTING -> conflict.getNewFileMd5();
            default -> null;
        };
        String resolutionNote = truncate(note, MAX_NOTE_CHARS);
        if (deletedMd5 != null) {
            boolean deleted = deleteFile(deletedMd5);
            if (!deleted) {
                resolutionNote = truncate(joinNote(resolutionNote, "待删除文件已不存在: " + deletedMd5), MAX_NOTE_CHARS);
            }
        }

        conflict.setStatus(action);
        conflict.setResolvedBy(operator);
        conflict.setResolvedAt(LocalDateTime.now());
        conflict.setResolutionNote(resolutionNote);
        DocumentConflict saved = conflictRepository.save(conflict);
        logger.warn("管理员处理文档冲突: id={}, action={}, operator={}, deletedFile={}", id, action, operator, deletedMd5);

        if (deletedMd5 != null) {
            obsoleteSiblings(deletedMd5, id, operator);
        }
        return saved;
    }

    /**
     * 通过现有的文档删除流程删除（ES 各版本索引、MinIO、分块、上传记录）
     *
     * @return false 表示文件已不存在
     */
    private boolean deleteFile(String fileMd5) {
        Optional<FileUpload> upload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5);
        if (upload.isEmpty()) {
            logger.warn("冲突处理时待删除文件已不存在: fileMd5={}", fileMd5);
            return false;
        }
        documentService.deleteDocument(fileMd5, upload.get().getUserId());
        return true;
    }

    /**
     * 被删除文件参与的其他待处理冲突失去意义，标记为 OBSOLETE（只改状态，不删任何文档）
     */
    private void obsoleteSiblings(String deletedMd5, Long resolvedId, String operator) {
        List<DocumentConflict> siblings = conflictRepository.findByStatusAndFileMd5(Status.PENDING, deletedMd5);
        for (DocumentConflict sibling : siblings) {
            if (sibling.getId().equals(resolvedId)) {
                continue;
            }
            sibling.setStatus(Status.OBSOLETE);
            sibling.setResolvedBy(operator);
            sibling.setResolvedAt(LocalDateTime.now());
            sibling.setResolutionNote("文件 " + deletedMd5 + " 已在处理冲突 #" + resolvedId + " 时删除");
            conflictRepository.save(sibling);
        }
        if (!siblings.isEmpty()) {
            logger.info("文件删除后关联冲突自动失效: fileMd5={}, count={}", deletedMd5, siblings.size());
        }
    }

    private DocumentConflict toEntity(ConflictCandidate candidate, String pairKey, Map<String, String> fileNames) {
        int snippetChars = Math.min(Math.max(properties.getSnippetChars(), 20), 990);
        DocumentConflict entity = new DocumentConflict();
        entity.setPairKey(pairKey);
        entity.setNewFileMd5(candidate.newChunk().fileMd5());
        entity.setNewChunkId(candidate.newChunk().chunkId());
        entity.setNewFileName(fileNames.get(candidate.newChunk().fileMd5()));
        entity.setExistingFileMd5(candidate.existingChunk().fileMd5());
        entity.setExistingChunkId(candidate.existingChunk().chunkId());
        entity.setExistingFileName(fileNames.get(candidate.existingChunk().fileMd5()));
        entity.setSimilarity(candidate.similarity());
        entity.setMaskedSimilarity(candidate.maskedSimilarity());
        entity.setNewSnippet(truncate(candidate.newChunk().text(), snippetChars));
        entity.setExistingSnippet(truncate(candidate.existingChunk().text(), snippetChars));
        entity.setNewValues(truncate(String.join(", ", candidate.newOnlyValues()), MAX_VALUES_CHARS));
        entity.setExistingValues(truncate(String.join(", ", candidate.existingOnlyValues()), MAX_VALUES_CHARS));
        entity.setLlmReason(truncate(candidate.llmReason(), MAX_NOTE_CHARS));
        entity.setStatus(Status.PENDING);
        return entity;
    }

    private Map<String, String> lookupFileNames(List<ConflictCandidate> candidates) {
        List<String> md5s = candidates.stream()
                .flatMap(c -> Stream.of(c.newChunk().fileMd5(), c.existingChunk().fileMd5()))
                .distinct()
                .toList();
        try {
            return fileUploadRepository.findByFileMd5In(md5s).stream()
                    .filter(upload -> upload.getFileName() != null)
                    .collect(Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName, (a, b) -> a));
        } catch (Exception e) {
            logger.warn("查询冲突文件名失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String joinNote(String note, String extra) {
        return note == null || note.isBlank() ? extra : note + "；" + extra;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }
}
