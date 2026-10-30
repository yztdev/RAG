package com.yizhaoqi.smartpai.service.conflict;

import com.yizhaoqi.smartpai.config.DocumentConflictProperties;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.DocumentConflict;
import com.yizhaoqi.smartpai.model.DocumentConflict.Status;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.DocumentConflictRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentConflictServiceTest {

    private final DocumentConflictRepository repository = mock(DocumentConflictRepository.class);
    private final FileUploadRepository fileUploadRepository = mock(FileUploadRepository.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private DocumentConflictService service;

    @BeforeEach
    void setUp() {
        service = new DocumentConflictService(repository, fileUploadRepository, documentService, new DocumentConflictProperties());
        when(repository.save(any(DocumentConflict.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fileUploadRepository.findByFileMd5In(anyList())).thenReturn(List.of(upload("new", "u1", "new.pdf"), upload("old", "u2", "old.pdf")));
    }

    @Test
    void pairKeyIsDirectionIndependent() {
        ConflictCandidate forward = candidate("aaa", 3, "bbb", 7);
        ConflictCandidate backward = candidate("bbb", 7, "aaa", 3);
        assertEquals(forward.pairKey(), backward.pairKey());
        assertEquals("aaa#3|bbb#7", forward.pairKey());
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordCandidatesSkipsPairsThatArePendingOrIgnored() {
        ConflictCandidate duplicate = candidate("new", 1, "old", 2);
        ConflictCandidate fresh = candidate("new", 3, "old", 4);
        when(repository.existsByPairKeyAndStatusIn(eq(duplicate.pairKey()), any())).thenReturn(true);
        when(repository.existsByPairKeyAndStatusIn(eq(fresh.pairKey()), any())).thenReturn(false);

        List<DocumentConflict> saved = service.recordCandidates(List.of(duplicate, fresh));

        assertEquals(1, saved.size());
        DocumentConflict conflict = saved.get(0);
        assertEquals(fresh.pairKey(), conflict.getPairKey());
        assertEquals(Status.PENDING, conflict.getStatus());
        assertEquals("new.pdf", conflict.getNewFileName());
        assertEquals("old.pdf", conflict.getExistingFileName());
        assertEquals("number:8080", conflict.getNewValues());
        assertEquals("number:9090", conflict.getExistingValues());
        ArgumentCaptor<Collection<Status>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(repository, times(2)).existsByPairKeyAndStatusIn(anyString(), statuses.capture());
        assertEquals(Set.of(Status.PENDING, Status.IGNORED), Set.copyOf(statuses.getValue()));
    }

    @Test
    void keepNewDeletesExistingDocumentThroughDeletionFlow() {
        DocumentConflict conflict = pending(1L);
        DocumentConflict sibling = pending(2L);
        when(repository.findById(1L)).thenReturn(Optional.of(conflict));
        when(fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc("old")).thenReturn(Optional.of(upload("old", "u2", "old.pdf")));
        when(repository.findByStatusAndFileMd5(Status.PENDING, "old")).thenReturn(List.of(conflict, sibling));

        DocumentConflict resolved = service.resolve(1L, Status.KEEP_NEW, "admin", "新文档是最新版");

        verify(documentService).deleteDocument("old", "u2");
        verify(documentService, never()).deleteDocument(eq("new"), anyString());
        assertEquals(Status.KEEP_NEW, resolved.getStatus());
        assertEquals("admin", resolved.getResolvedBy());
        assertNotNull(resolved.getResolvedAt());
        assertEquals("新文档是最新版", resolved.getResolutionNote());
        assertEquals(Status.OBSOLETE, sibling.getStatus());
    }

    @Test
    void keepExistingDeletesNewDocument() {
        when(repository.findById(1L)).thenReturn(Optional.of(pending(1L)));
        when(fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc("new")).thenReturn(Optional.of(upload("new", "u1", "new.pdf")));
        when(repository.findByStatusAndFileMd5(Status.PENDING, "new")).thenReturn(List.of());

        DocumentConflict resolved = service.resolve(1L, Status.KEEP_EXISTING, "admin", null);

        verify(documentService).deleteDocument("new", "u1");
        assertEquals(Status.KEEP_EXISTING, resolved.getStatus());
    }

    @Test
    void ignoredOnlyMarksConflict() {
        when(repository.findById(1L)).thenReturn(Optional.of(pending(1L)));

        DocumentConflict resolved = service.resolve(1L, Status.IGNORED, "admin", "不同环境的配置");

        verify(documentService, never()).deleteDocument(anyString(), anyString());
        verify(repository, never()).findByStatusAndFileMd5(any(), anyString());
        assertEquals(Status.IGNORED, resolved.getStatus());
    }

    @Test
    void resolvingAlreadyResolvedConflictIsRejected() {
        DocumentConflict conflict = pending(1L);
        conflict.setStatus(Status.IGNORED);
        when(repository.findById(1L)).thenReturn(Optional.of(conflict));

        CustomException error = assertThrows(CustomException.class, () -> service.resolve(1L, Status.KEEP_NEW, "admin", null));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(documentService, never()).deleteDocument(anyString(), anyString());
    }

    @Test
    void pendingAndObsoleteAreNotValidActions() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(CustomException.class, () -> service.resolve(1L, Status.PENDING, "admin", null)).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(CustomException.class, () -> service.resolve(1L, Status.OBSOLETE, "admin", null)).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(CustomException.class, () -> service.resolve(1L, null, "admin", null)).getStatus());
    }

    @Test
    void unknownConflictIsNotFound() {
        when(repository.findById(9L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(CustomException.class, () -> service.resolve(9L, Status.IGNORED, "admin", null)).getStatus());
    }

    @Test
    void deletionFailureKeepsConflictPending() {
        DocumentConflict conflict = pending(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(conflict));
        when(fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc("old")).thenReturn(Optional.of(upload("old", "u2", "old.pdf")));
        doThrow(new RuntimeException("minio down")).when(documentService).deleteDocument("old", "u2");

        assertThrows(RuntimeException.class, () -> service.resolve(1L, Status.KEEP_NEW, "admin", null));

        assertEquals(Status.PENDING, conflict.getStatus());
        verify(repository, never()).save(any(DocumentConflict.class));
    }

    @Test
    void missingFileIsNotedButConflictStillResolved() {
        when(repository.findById(1L)).thenReturn(Optional.of(pending(1L)));
        when(fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc("old")).thenReturn(Optional.empty());
        when(repository.findByStatusAndFileMd5(Status.PENDING, "old")).thenReturn(List.of());

        DocumentConflict resolved = service.resolve(1L, Status.KEEP_NEW, "admin", null);

        verify(documentService, never()).deleteDocument(anyString(), anyString());
        assertEquals(Status.KEEP_NEW, resolved.getStatus());
        assertTrue(resolved.getResolutionNote().contains("已不存在"));
    }

    private static ConflictCandidate candidate(String newMd5, int newChunk, String oldMd5, int oldChunk) {
        return new ConflictCandidate(
                new ConflictCandidate.ChunkRef(newMd5, newChunk, "端口 8080"),
                new ConflictCandidate.ChunkRef(oldMd5, oldChunk, "端口 9090"),
                0.97, 1.0, Set.of("number:8080"), Set.of("number:9090"), null);
    }

    private static DocumentConflict pending(Long id) {
        DocumentConflict conflict = new DocumentConflict();
        conflict.setId(id);
        conflict.setNewFileMd5("new");
        conflict.setNewChunkId(1);
        conflict.setExistingFileMd5("old");
        conflict.setExistingChunkId(2);
        conflict.setStatus(Status.PENDING);
        return conflict;
    }

    private static FileUpload upload(String md5, String userId, String name) {
        FileUpload upload = new FileUpload();
        upload.setFileMd5(md5);
        upload.setUserId(userId);
        upload.setFileName(name);
        return upload;
    }
}
