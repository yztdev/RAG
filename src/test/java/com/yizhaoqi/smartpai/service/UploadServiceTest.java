package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.config.MinioConfig;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.ChunkInfo;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.ChunkInfoRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UploadServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private MinioClient minioClient;

    @Mock
    private FileUploadRepository fileUploadRepository;

    @Mock
    private ChunkInfoRepository chunkInfoRepository;

    @Mock
    private MinioConfig minioConfig;

    private UploadService uploadService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        uploadService = new UploadService();
        ReflectionTestUtils.setField(uploadService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(uploadService, "minioClient", minioClient);
        ReflectionTestUtils.setField(uploadService, "fileUploadRepository", fileUploadRepository);
        ReflectionTestUtils.setField(uploadService, "chunkInfoRepository", chunkInfoRepository);
        ReflectionTestUtils.setField(uploadService, "minioConfig", minioConfig);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void uploadChunkRejectsWhenFileAlreadyCompleted() throws Exception {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setFileMd5("md5");
        fileUpload.setUserId("1");
        fileUpload.setStatus(FileUpload.STATUS_COMPLETED);

        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        CustomException exception = assertThrows(
                CustomException.class,
                () -> uploadService.uploadChunk("md5", 0, 1024L, "test.pdf", file, "TEAM_A", false, "1")
        );

        assertEquals("文件已完成合并，不允许继续上传分片", exception.getMessage());
        verifyNoInteractions(chunkInfoRepository);
    }

    @Test
    void uploadChunkRejectsWhenFileIsMerging() throws Exception {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setFileMd5("md5");
        fileUpload.setUserId("1");
        fileUpload.setStatus(FileUpload.STATUS_MERGING);

        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        CustomException exception = assertThrows(
                CustomException.class,
                () -> uploadService.uploadChunk("md5", 0, 1024L, "test.pdf", file, "TEAM_A", false, "1")
        );

        assertEquals("文件正在合并中，请稍后重试", exception.getMessage());
        verifyNoInteractions(chunkInfoRepository);
    }

    @Test
    void uploadChunkIsIdempotentWhenRedisDatabaseAndObjectAllHaveChunk() throws Exception {
        FileUpload fileUpload = uploadingFile();
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));
        when(valueOperations.getBit("upload:1:md5", 0)).thenReturn(true);
        when(chunkInfoRepository.existsByFileMd5AndUserIdAndChunkIndex("md5", "1", 0)).thenReturn(true);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        uploadService.uploadChunk("md5", 0, 1024L, "test.pdf", file, "TEAM_A", false, "1");

        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
        verify(chunkInfoRepository, never()).save(any(ChunkInfo.class));
    }

    @Test
    void uploadChunkBackfillsRedisWhenDatabaseHasChunkAfterRedisMiss() throws Exception {
        FileUpload fileUpload = uploadingFile();
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));
        when(valueOperations.getBit("upload:1:md5", 0)).thenReturn(false);
        when(chunkInfoRepository.existsByFileMd5AndUserIdAndChunkIndex("md5", "1", 0)).thenReturn(true);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        uploadService.uploadChunk("md5", 0, 1024L, "test.pdf", file, "TEAM_A", false, "1");

        verify(valueOperations).setBit("upload:1:md5", 0, true);
        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void uploadChunkWritesDatabaseBeforeRedisForNewChunk() throws Exception {
        FileUpload fileUpload = uploadingFile();
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));
        when(valueOperations.getBit("upload:1:md5", 0)).thenReturn(false);
        when(chunkInfoRepository.existsByFileMd5AndUserIdAndChunkIndex("md5", "1", 0)).thenReturn(false);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        uploadService.uploadChunk("md5", 0, 1024L, "test.pdf", file, "TEAM_A", false, "1");

        InOrder inOrder = inOrder(minioClient, chunkInfoRepository, valueOperations);
        inOrder.verify(minioClient).putObject(any(PutObjectArgs.class));
        inOrder.verify(chunkInfoRepository).save(any(ChunkInfo.class));
        inOrder.verify(valueOperations).setBit("upload:1:md5", 0, true);
    }

    @Test
    void newChunkIsStoredUnderUserScopedPathAndRecordCarriesUserId() throws Exception {
        FileUpload fileUpload = uploadingFile();
        fileUpload.setId(10L);
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));
        when(valueOperations.getBit("upload:1:md5", 3)).thenReturn(false);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        uploadService.uploadChunk("md5", 3, 1024L, "test.pdf", file, "TEAM_A", false, "1");

        ArgumentCaptor<PutObjectArgs> put = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(put.capture());
        assertEquals("chunks/1/md5/3", put.getValue().object());
        ArgumentCaptor<ChunkInfo> saved = ArgumentCaptor.forClass(ChunkInfo.class);
        verify(chunkInfoRepository).save(saved.capture());
        assertEquals("1", saved.getValue().getUserId());
        assertEquals("chunks/1/md5/3", saved.getValue().getStoragePath());
        // bitmap 带过期时间，上传心跳同步刷新
        verify(redisTemplate).expire(eq("upload:1:md5"), eq(Duration.ofHours(48)));
        verify(fileUploadRepository).touchUploadHeartbeat(eq(10L), eq(FileUpload.STATUS_UPLOADING), any(LocalDateTime.class));
    }

    @Test
    void mergeCompletesWithConditionalUpdateBeforeDeletingChunks() throws Exception {
        FileUpload merging = mergingFile();
        stubMergeableChunks(merging);
        when(fileUploadRepository.completeMergeIfMerging(eq(10L), eq(FileUpload.STATUS_MERGING), eq(FileUpload.STATUS_COMPLETED), any()))
                .thenReturn(1);

        uploadService.mergeChunks("md5", "test.pdf", "1");

        InOrder inOrder = inOrder(fileUploadRepository, minioClient, chunkInfoRepository);
        inOrder.verify(fileUploadRepository).completeMergeIfMerging(eq(10L), eq(FileUpload.STATUS_MERGING), eq(FileUpload.STATUS_COMPLETED), any());
        inOrder.verify(minioClient).removeObject(any(RemoveObjectArgs.class));
        inOrder.verify(chunkInfoRepository).deleteByFileMd5AndUserId("md5", "1");
        verify(fileUploadRepository, never()).save(any(FileUpload.class));
    }

    @Test
    void mergeTreatsAlreadyCompletedAsIdempotentSuccess() throws Exception {
        FileUpload merging = mergingFile();
        stubMergeableChunks(merging);
        when(fileUploadRepository.completeMergeIfMerging(eq(10L), eq(FileUpload.STATUS_MERGING), eq(FileUpload.STATUS_COMPLETED), any()))
                .thenReturn(0);
        FileUpload completed = mergingFile();
        completed.setStatus(FileUpload.STATUS_COMPLETED);
        when(fileUploadRepository.findById(10L)).thenReturn(Optional.of(completed));

        uploadService.mergeChunks("md5", "test.pdf", "1");

        verify(chunkInfoRepository).deleteByFileMd5AndUserId("md5", "1");
    }

    @Test
    void mergeKeepsChunksWhenStatusChangedUnderneath() throws Exception {
        FileUpload merging = mergingFile();
        stubMergeableChunks(merging);
        when(fileUploadRepository.completeMergeIfMerging(eq(10L), eq(FileUpload.STATUS_MERGING), eq(FileUpload.STATUS_COMPLETED), any()))
                .thenReturn(0);
        FileUpload reset = mergingFile();
        reset.setStatus(FileUpload.STATUS_UPLOADING);
        when(fileUploadRepository.findById(10L)).thenReturn(Optional.of(reset));

        CustomException exception = assertThrows(CustomException.class,
                () -> uploadService.mergeChunks("md5", "test.pdf", "1"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
        verify(chunkInfoRepository, never()).deleteByFileMd5AndUserId(anyString(), anyString());
    }

    private void stubMergeableChunks(FileUpload merging) throws Exception {
        ChunkInfo chunk = new ChunkInfo();
        chunk.setFileMd5("md5");
        chunk.setUserId("1");
        chunk.setChunkIndex(0);
        chunk.setStoragePath("chunks/1/md5/0");
        when(chunkInfoRepository.findByFileMd5AndUserIdOrderByChunkIndexAsc("md5", "1")).thenReturn(List.of(chunk));
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(merging));
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(mock(StatObjectResponse.class));
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn("https://example.com/merged/md5");
    }

    private FileUpload mergingFile() {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setId(10L);
        fileUpload.setFileMd5("md5");
        fileUpload.setUserId("1");
        fileUpload.setTotalSize(1024L);
        fileUpload.setStatus(FileUpload.STATUS_MERGING);
        return fileUpload;
    }

    private FileUpload uploadingFile() {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setFileMd5("md5");
        fileUpload.setUserId("1");
        fileUpload.setStatus(FileUpload.STATUS_UPLOADING);
        return fileUpload;
    }
}
