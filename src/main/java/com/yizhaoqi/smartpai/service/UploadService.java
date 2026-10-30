package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.config.FileProcessingReliabilityProperties;
import com.yizhaoqi.smartpai.config.MinioConfig;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.ChunkInfo;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.ChunkInfoRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import io.micrometer.common.util.StringUtils;
import io.minio.*;
import io.minio.http.Method;
import io.minio.GetObjectResponse;
import io.minio.messages.Item;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class UploadService {

    private static final Logger logger = LoggerFactory.getLogger(UploadService.class);
    private static final ConcurrentHashMap<String, Object> FILE_UPLOAD_CREATE_LOCKS = new ConcurrentHashMap<>();

    // 用于缓存已上传分片的信息
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // 用于与 MinIO 服务器交互
    @Autowired
    private MinioClient minioClient;

    // 用于操作文件上传记录的 Repository
    @Autowired
    private FileUploadRepository fileUploadRepository;

    // 用于操作分片信息的 Repository
    @Autowired
    private ChunkInfoRepository chunkInfoRepository;

    @Autowired
    private MinioConfig minioConfig;

    @Autowired
    private FileProcessingReliabilityProperties reliabilityProperties = new FileProcessingReliabilityProperties();

    /**
     * 上传文件分片
     *
     * @param fileMd5 文件的 MD5 值，用于唯一标识文件
     * @param chunkIndex 分片索引，表示这是文件的第几个分片
     * @param totalSize 文件总大小
     * @param fileName 文件名称
     * @param file 要上传的分片文件
     * @param orgTag 组织标签，指定文件所属的组织
     * @param isPublic 是否公开，标识文件访问权限
     * @param userId 上传用户ID
     * @throws IOException 如果文件读取失败
     */
    public void uploadChunk(String fileMd5, int chunkIndex, long totalSize, String fileName, 
                           MultipartFile file, String orgTag, boolean isPublic, String userId) throws IOException {
        // 获取文件类型信息
        String fileType = getFileType(fileName);
        String contentType = file.getContentType();
        
        logger.info("[uploadChunk] 开始处理分片上传请求 => fileMd5: {}, chunkIndex: {}, totalSize: {}, fileName: {}, fileType: {}, contentType: {}, fileSize: {}, orgTag: {}, isPublic: {}, userId: {}", 
                   fileMd5, chunkIndex, totalSize, fileName, fileType, contentType, file.getSize(), orgTag, isPublic, userId);
        
        try {
            FileUpload fileUpload = getOrCreateFileUpload(fileMd5, totalSize, fileName, orgTag, isPublic, userId, fileType);
            logger.debug("检查文件记录是否存在 => fileMd5: {}, fileName: {}, fileType: {}, status: {}", fileMd5, fileName, fileType, fileUpload.getStatus());

            if (fileUpload.getStatus() == FileUpload.STATUS_MERGING) {
                throw new CustomException("文件正在合并中，请稍后重试", HttpStatus.CONFLICT);
            }
            if (fileUpload.getStatus() == FileUpload.STATUS_COMPLETED) {
                throw new CustomException("文件已完成合并，不允许继续上传分片", HttpStatus.CONFLICT);
            }

            String storagePath = buildChunkStoragePath(userId, fileMd5, chunkIndex);

            // Redis Bitmap 是上传进度快路径；数据库 + MinIO 对象共同决定分片是否可用于合并。
            boolean chunkUploaded = isChunkUploaded(fileMd5, chunkIndex, userId);
            boolean chunkInfoExists = chunkInfoRepository.existsByFileMd5AndUserIdAndChunkIndex(fileMd5, userId, chunkIndex);
            logger.debug("检查分片是否已上传 => fileMd5: {}, fileName: {}, chunkIndex: {}, isUploaded: {}", 
                      fileMd5, fileName, chunkIndex, chunkUploaded);

            if (chunkUploaded && chunkInfoExists && chunkObjectExists(storagePath, fileMd5, fileName, chunkIndex)) {
                logger.info("分片已在Redis中标记为已上传，按幂等成功处理 => fileMd5: {}, fileName: {}, fileType: {}, chunkIndex: {}", fileMd5, fileName, fileType, chunkIndex);
                return;
            }

            if (chunkUploaded || chunkInfoExists) {
                clearStaleChunkState(fileMd5, chunkIndex, userId, fileName, storagePath);
            }

            if (chunkInfoRepository.existsByFileMd5AndUserIdAndChunkIndex(fileMd5, userId, chunkIndex)
                    && chunkObjectExists(storagePath, fileMd5, fileName, chunkIndex)) {
                logger.info("Redis未命中但数据库已有分片信息，回填Redis后按幂等成功处理 => fileMd5: {}, fileName: {}, chunkIndex: {}", fileMd5, fileName, chunkIndex);
                markChunkUploadedQuietly(fileMd5, chunkIndex, userId, fileName);
                return;
            }

            logger.debug("计算分片MD5 => fileMd5: {}, fileName: {}, chunkIndex: {}", fileMd5, fileName, chunkIndex);
            byte[] fileBytes = file.getBytes();
            String chunkMd5 = DigestUtils.md5Hex(fileBytes);
            logger.debug("分片MD5计算完成 => fileMd5: {}, fileName: {}, chunkIndex: {}, chunkMd5: {}",
                       fileMd5, fileName, chunkIndex, chunkMd5);

            logger.debug("构建分片存储路径 => fileName: {}, path: {}", fileName, storagePath);

            try {
                logger.info("开始上传分片到MinIO => fileMd5: {}, fileName: {}, fileType: {}, chunkIndex: {}, bucket: uploads, path: {}, size: {}, contentType: {}",
                          fileMd5, fileName, fileType, chunkIndex, storagePath, file.getSize(), contentType);

                PutObjectArgs putObjectArgs = PutObjectArgs.builder()
                        .bucket("uploads")
                        .object(storagePath)
                        .stream(file.getInputStream(), file.getSize(), -1)
                        .contentType(file.getContentType())
                        .build();

                minioClient.putObject(putObjectArgs);
                logger.info("分片上传到MinIO成功 => fileMd5: {}, fileName: {}, fileType: {}, chunkIndex: {}", fileMd5, fileName, fileType, chunkIndex);
            } catch (Exception e) {
                logger.error("分片上传到MinIO失败 => fileMd5: {}, fileName: {}, fileType: {}, chunkIndex: {}, 错误类型: {}, 错误信息: {}",
                          fileMd5, fileName, fileType, chunkIndex, e.getClass().getName(), e.getMessage(), e);

                if (e instanceof io.minio.errors.ErrorResponseException) {
                    io.minio.errors.ErrorResponseException ere = (io.minio.errors.ErrorResponseException) e;
                    logger.error("MinIO错误响应详情 => fileName: {}, code: {}, message: {}, resource: {}, requestId: {}",
                             fileName, ere.errorResponse().code(), ere.errorResponse().message(),
                             ere.errorResponse().resource(), ere.errorResponse().requestId());
                }

                throw new RuntimeException("上传分片到MinIO失败: " + e.getMessage(), e);
            }

            logger.debug("保存分片信息到数据库 => fileMd5: {}, fileName: {}, chunkIndex: {}, chunkMd5: {}, storagePath: {}",
                      fileMd5, fileName, chunkIndex, chunkMd5, storagePath);
            saveChunkInfo(fileMd5, userId, chunkIndex, chunkMd5, storagePath);
            logger.info("分片信息已保存到数据库 => fileMd5: {}, fileName: {}, chunkIndex: {}", fileMd5, fileName, chunkIndex);

            markChunkUploadedQuietly(fileMd5, chunkIndex, userId, fileName);
            touchUploadHeartbeat(fileUpload);
            
            logger.info("分片处理完成 => fileMd5: {}, fileName: {}, fileType: {}, chunkIndex: {}", fileMd5, fileName, fileType, chunkIndex);
        } catch (Exception e) {
            logger.error("分片上传过程中发生错误 => fileMd5: {}, fileName: {}, fileType: {}, chunkIndex: {}, 错误类型: {}, 错误信息: {}", 
                       fileMd5, fileName, fileType, chunkIndex, e.getClass().getName(), e.getMessage(), e);
            throw e; // 重新抛出异常供上层处理
        }
    }

    /**
     * 根据文件名获取文件类型
     *
     * @param fileName 文件名
     * @return 文件类型
     */
    private String getFileType(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "unknown";
        }
        
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return "unknown";
        }
        
        String extension = fileName.substring(lastDotIndex + 1).toLowerCase();
        
        // 根据文件扩展名返回文件类型
        switch (extension) {
            case "pdf":
                return "PDF文档";
            case "doc":
            case "docx":
                return "Word文档";
            case "xls":
            case "xlsx":
                return "Excel表格";
            case "ppt":
            case "pptx":
                return "PowerPoint演示文稿";
            case "txt":
                return "文本文件";
            case "md":
                return "Markdown文档";
            case "jpg":
            case "jpeg":
                return "JPEG图片";
            case "png":
                return "PNG图片";
            case "gif":
                return "GIF图片";
            case "bmp":
                return "BMP图片";
            case "svg":
                return "SVG图片";
            case "mp4":
                return "MP4视频";
            case "avi":
                return "AVI视频";
            case "mov":
                return "MOV视频";
            case "wmv":
                return "WMV视频";
            case "mp3":
                return "MP3音频";
            case "wav":
                return "WAV音频";
            case "flac":
                return "FLAC音频";
            case "zip":
                return "ZIP压缩包";
            case "rar":
                return "RAR压缩包";
            case "7z":
                return "7Z压缩包";
            case "tar":
                return "TAR压缩包";
            case "gz":
                return "GZ压缩包";
            case "json":
                return "JSON文件";
            case "xml":
                return "XML文件";
            case "csv":
                return "CSV文件";
            case "html":
            case "htm":
                return "HTML文件";
            case "css":
                return "CSS文件";
            case "js":
                return "JavaScript文件";
            case "java":
                return "Java源码";
            case "py":
                return "Python源码";
            case "cpp":
            case "c":
                return "C/C++源码";
            case "sql":
                return "SQL文件";
            default:
                return extension.toUpperCase() + "文件";
        }
    }

    /**
     * 检查指定分片是否已上传（单个查询版本，性能较低）
     * 注意：对于批量查询建议使用 getUploadedChunks() 方法
     *
     * @param fileMd5 文件的 MD5 值
     * @param chunkIndex 分片索引
     * @param userId 用户ID
     * @return 分片是否已上传
     */
    public boolean isChunkUploaded(String fileMd5, int chunkIndex, String userId) {
        logger.debug("检查分片是否已上传 => fileMd5: {}, chunkIndex: {}, userId: {}", fileMd5, chunkIndex, userId);
        try {
            if (chunkIndex < 0) {
                logger.error("无效的分片索引 => fileMd5: {}, chunkIndex: {}", fileMd5, chunkIndex);
                throw new IllegalArgumentException("chunkIndex must be non-negative");
            }
            String redisKey = "upload:" + userId + ":" + fileMd5;
            boolean isUploaded = redisTemplate.opsForValue().getBit(redisKey, chunkIndex);
            logger.debug("分片上传状态 => fileMd5: {}, chunkIndex: {}, userId: {}, isUploaded: {}", 
                      fileMd5, chunkIndex, userId, isUploaded);
            return isUploaded;
        } catch (Exception e) {
            logger.error("检查分片上传状态失败 => fileMd5: {}, chunkIndex: {}, userId: {}, 错误: {}", 
                      fileMd5, chunkIndex, userId, e.getMessage(), e);
            return false; // 或者根据业务需求返回其他值
        }
    }

    /**
     * 标记指定分片为已上传
     *
     * @param fileMd5 文件的 MD5 值
     * @param chunkIndex 分片索引
     * @param userId 用户ID
     */
    public void markChunkUploaded(String fileMd5, int chunkIndex, String userId) {
        logger.debug("标记分片为已上传 => fileMd5: {}, chunkIndex: {}, userId: {}", fileMd5, chunkIndex, userId);
        try {
            if (chunkIndex < 0) {
                logger.error("无效的分片索引 => fileMd5: {}, chunkIndex: {}", fileMd5, chunkIndex);
                throw new IllegalArgumentException("chunkIndex must be non-negative");
            }
            String redisKey = "upload:" + userId + ":" + fileMd5;
            redisTemplate.opsForValue().setBit(redisKey, chunkIndex, true);
            // 每次写入都续期：用户放弃上传后 bitmap 自动过期，不会永久残留
            redisTemplate.expire(redisKey, Duration.ofHours(Math.max(reliabilityProperties.getChunkBitmapTtlHours(), 1L)));
            logger.debug("分片已标记为已上传 => fileMd5: {}, chunkIndex: {}, userId: {}", fileMd5, chunkIndex, userId);
        } catch (Exception e) {
            logger.error("标记分片为已上传失败 => fileMd5: {}, chunkIndex: {}, userId: {}, 错误: {}", 
                      fileMd5, chunkIndex, userId, e.getMessage(), e);
            throw new RuntimeException("Failed to mark chunk as uploaded", e);
        }
    }

    /**
     * 删除文件所有分片上传标记
     *
     * @param fileMd5 文件的 MD5 值
     * @param userId 用户ID
     */
    public void deleteFileMark(String fileMd5, String userId) {
        logger.debug("删除文件所有分片上传标记 => fileMd5: {}, userId: {}", fileMd5, userId);
        try {
            String redisKey = "upload:" + userId + ":" + fileMd5;
            redisTemplate.delete(redisKey);
            logger.info("文件分片上传标记已删除 => fileMd5: {}, userId: {}", fileMd5, userId);
        } catch (Exception e) {
            logger.error("删除文件分片上传标记失败 => fileMd5: {}, userId: {}, 错误: {}", fileMd5, userId, e.getMessage(), e);
            throw new RuntimeException("Failed to delete file mark", e);
        }
    }


    /**
     * 获取已上传的分片列表
     *
     * @param fileMd5 文件的 MD5 值
     * @param userId 用户ID
     * @return 包含已上传分片索引的列表
     */
    public List<Integer> getUploadedChunks(String fileMd5, String userId) {
        logger.info("获取已上传分片列表 => fileMd5: {}, userId: {}", fileMd5, userId);
        List<Integer> uploadedChunks = new ArrayList<>();
        try {
            int totalChunks = getTotalChunks(fileMd5, userId);
            logger.debug("文件总分片数 => fileMd5: {}, userId: {}, totalChunks: {}", fileMd5, userId, totalChunks);
            
            if (totalChunks == 0) {
                logger.warn("文件总分片数为0 => fileMd5: {}, userId: {}", fileMd5, userId);
                return uploadedChunks;
            }
            
            // 优化：一次性获取所有分片状态
            String redisKey = "upload:" + userId + ":" + fileMd5;
            byte[] bitmapData = redisTemplate.execute((RedisCallback<byte[]>) connection -> {
                return connection.get(redisKey.getBytes());
            });
            
            if (bitmapData == null) {
                logger.info("Redis中无分片状态记录 => fileMd5: {}, userId: {}", fileMd5, userId);
                List<Integer> dbUploadedChunks = getUploadedChunksFromDatabase(fileMd5, userId);
                if (!dbUploadedChunks.isEmpty()) {
                    backfillUploadedChunks(fileMd5, dbUploadedChunks, userId);
                    logger.info("已从数据库回填Redis分片状态 => fileMd5: {}, userId: {}, 已上传数量: {}",
                              fileMd5, userId, dbUploadedChunks.size());
                }
                return dbUploadedChunks;
            }

            if (bitmapData.length == 0) {
                logger.info("Redis中分片状态为空，尝试从数据库回源 => fileMd5: {}, userId: {}", fileMd5, userId);
                List<Integer> dbUploadedChunks = getUploadedChunksFromDatabase(fileMd5, userId);
                if (!dbUploadedChunks.isEmpty()) {
                    backfillUploadedChunks(fileMd5, dbUploadedChunks, userId);
                }
                return dbUploadedChunks;
            }

            // 解析bitmap，找出已上传的分片
            for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
                if (isBitSet(bitmapData, chunkIndex)) {
                    uploadedChunks.add(chunkIndex);
                }
            }

            if (uploadedChunks.isEmpty()) {
                List<Integer> dbUploadedChunks = getUploadedChunksFromDatabase(fileMd5, userId);
                if (!dbUploadedChunks.isEmpty()) {
                    backfillUploadedChunks(fileMd5, dbUploadedChunks, userId);
                    logger.info("Redis无有效分片位，已从数据库回填 => fileMd5: {}, userId: {}, 已上传数量: {}",
                              fileMd5, userId, dbUploadedChunks.size());
                    return dbUploadedChunks;
                }
                return uploadedChunks;
            }
            
            logger.info("获取到已上传分片列表 => fileMd5: {}, userId: {}, 已上传数量: {}, 总分片数: {}, 优化方式: 一次性获取", 
                      fileMd5, userId, uploadedChunks.size(), totalChunks);
            return uploadedChunks;
        } catch (Exception e) {
            logger.error("获取已上传分片列表失败 => fileMd5: {}, userId: {}, 错误: {}", fileMd5, userId, e.getMessage(), e);
            throw new RuntimeException("Failed to get uploaded chunks", e);
        }
    }

    private List<Integer> getUploadedChunksFromDatabase(String fileMd5, String userId) {
        logger.debug("从数据库查询已上传分片列表 => fileMd5: {}, userId: {}", fileMd5, userId);
        return chunkInfoRepository.findChunkIndexesByFileMd5AndUserId(fileMd5, userId);
    }

    private void backfillUploadedChunks(String fileMd5, List<Integer> uploadedChunks, String userId) {
        for (Integer chunkIndex : uploadedChunks) {
            if (chunkIndex != null && chunkIndex >= 0) {
                markChunkUploadedQuietly(fileMd5, chunkIndex, userId, "unknown");
            }
        }
    }

    private void markChunkUploadedQuietly(String fileMd5, int chunkIndex, String userId, String fileName) {
        try {
            logger.debug("标记分片为已上传 => fileMd5: {}, fileName: {}, chunkIndex: {}", fileMd5, fileName, chunkIndex);
            markChunkUploaded(fileMd5, chunkIndex, userId);
            logger.debug("分片标记完成 => fileMd5: {}, fileName: {}, chunkIndex: {}", fileMd5, fileName, chunkIndex);
        } catch (Exception e) {
            logger.error("标记分片已上传失败，数据库仍作为事实来源 => fileMd5: {}, fileName: {}, chunkIndex: {}, 错误: {}",
                      fileMd5, fileName, chunkIndex, e.getMessage(), e);
        }
    }

    /**
     * 检查bitmap中指定位置是否为1
     *
     * @param bitmapData bitmap数据
     * @param bitIndex 位索引
     * @return 指定位置是否为1
     */
    private boolean isBitSet(byte[] bitmapData, int bitIndex) {
        try {
            int byteIndex = bitIndex / 8;
            int bitPosition = 7 - (bitIndex % 8); // Redis bitmap的位顺序是从高位到低位
            
            if (byteIndex >= bitmapData.length) {
                return false; // 超出范围的位默认为0
            }
            
            return (bitmapData[byteIndex] & (1 << bitPosition)) != 0;
        } catch (Exception e) {
            logger.error("检查bitmap位状态失败 => bitIndex: {}, 错误: {}", bitIndex, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取文件的总分片数
     *
     * @param fileMd5 文件的 MD5 值
     * @param userId 用户ID
     * @return 文件的总分片数
     */
    public int getTotalChunks(String fileMd5, String userId) {
        logger.info("计算文件总分片数 => fileMd5: {}, userId: {}", fileMd5, userId);
        try {
            Optional<FileUpload> fileUpload = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId);
            
            if (fileUpload.isEmpty()) {
                logger.warn("文件记录不存在，无法计算分片数 => fileMd5: {}, userId: {}", fileMd5, userId);
                return 0;
            }
            
            long totalSize = fileUpload.get().getTotalSize();
            // 默认每个分片5MB
            int chunkSize = 5 * 1024 * 1024;
            int totalChunks = (int) Math.ceil((double) totalSize / chunkSize);
            
            logger.info("文件总分片数计算结果 => fileMd5: {}, userId: {}, totalSize: {}, chunkSize: {}, totalChunks: {}", 
                      fileMd5, userId, totalSize, chunkSize, totalChunks);
            return totalChunks;
        } catch (Exception e) {
            logger.error("计算文件总分片数失败 => fileMd5: {}, userId: {}, 错误: {}", fileMd5, userId, e.getMessage(), e);
            throw new RuntimeException("Failed to calculate total chunks", e);
        }
    }

    /**
     * 保存分片信息到数据库
     *
     * @param fileMd5 文件的 MD5 值
     * @param userId 上传用户ID
     * @param chunkIndex 分片索引
     * @param chunkMd5 分片的 MD5 值
     * @param storagePath 分片的存储路径
     */
    private void saveChunkInfo(String fileMd5, String userId, int chunkIndex, String chunkMd5, String storagePath) {
        logger.debug("保存分片信息到数据库 => fileMd5: {}, userId: {}, chunkIndex: {}, chunkMd5: {}, storagePath: {}",
                   fileMd5, userId, chunkIndex, chunkMd5, storagePath);
        try {
            ChunkInfo chunkInfo = new ChunkInfo();
            chunkInfo.setFileMd5(fileMd5);
            chunkInfo.setUserId(userId);
            chunkInfo.setChunkIndex(chunkIndex);
            chunkInfo.setChunkMd5(chunkMd5);
            chunkInfo.setStoragePath(storagePath);
            
            chunkInfoRepository.save(chunkInfo);
            logger.debug("分片信息已保存 => fileMd5: {}, chunkIndex: {}", fileMd5, chunkIndex);
        } catch (DataIntegrityViolationException e) {
            logger.info("分片信息已存在，按幂等成功处理 => fileMd5: {}, chunkIndex: {}", fileMd5, chunkIndex);
        } catch (Exception e) {
            logger.error("保存分片信息失败 => fileMd5: {}, chunkIndex: {}, 错误: {}", 
                      fileMd5, chunkIndex, e.getMessage(), e);
            throw new RuntimeException("Failed to save chunk info", e);
        }
    }

    /**
     * 合并所有分片
     *
     * @param fileMd5 文件的 MD5 值
     * @param fileName 文件名
     * @param userId 用户ID
     * @return 合成文件的访问 URL
     */
    public String mergeChunks(String fileMd5, String fileName, String userId) {
        String fileType = getFileType(fileName);
        logger.info("开始合并文件分片 => fileMd5: {}, fileName: {}, fileType: {}, userId: {}", fileMd5, fileName, fileType, userId);
        try {
            // 查询所有分片信息
            logger.debug("查询分片信息 => fileMd5: {}, fileName: {}", fileMd5, fileName);
            List<ChunkInfo> chunks = chunkInfoRepository.findByFileMd5AndUserIdOrderByChunkIndexAsc(fileMd5, userId);
            logger.info("查询到分片信息 => fileMd5: {}, fileName: {}, fileType: {}, 分片数量: {}", fileMd5, fileName, fileType, chunks.size());
            
            // 检查分片数量是否与预期一致
            int expectedChunks = getTotalChunks(fileMd5, userId);
            if (chunks.size() != expectedChunks) {
                logger.error("分片数量不匹配 => fileMd5: {}, fileName: {}, fileType: {}, 期望: {}, 实际: {}", 
                          fileMd5, fileName, fileType, expectedChunks, chunks.size());
                throw new RuntimeException(String.format(
                    "分片数量不匹配，期望: %d, 实际: %d", expectedChunks, chunks.size()));
            }
            
            List<String> partPaths = chunks.stream()
                    .map(ChunkInfo::getStoragePath)
                    .collect(Collectors.toList());
            logger.debug("分片路径列表 => fileMd5: {}, fileName: {}, 路径数量: {}", fileMd5, fileName, partPaths.size());

            // 检查每个分片是否存在
            logger.info("开始检查每个分片是否存在 => fileMd5: {}, fileName: {}, fileType: {}", fileMd5, fileName, fileType);
            for (int i = 0; i < partPaths.size(); i++) {
                String path = partPaths.get(i);
                try {
                    StatObjectResponse stat = minioClient.statObject(
                        StatObjectArgs.builder()
                            .bucket("uploads")
                            .object(path)
                            .build()
                    );
                    logger.debug("分片存在 => fileName: {}, index: {}, path: {}, size: {}", fileName, i, path, stat.size());
                } catch (Exception e) {
                    logger.error("分片不存在或无法访问 => fileName: {}, index: {}, path: {}, 错误: {}", 
                              fileName, i, path, e.getMessage(), e);
                    throw new RuntimeException("分片 " + i + " 不存在或无法访问: " + e.getMessage(), e);
                }
            }
            logger.info("分片检查完成，所有分片都存在 => fileMd5: {}, fileName: {}, fileType: {}", fileMd5, fileName, fileType);
            
            // 使用 MD5 作为 MinIO 对象路径，确保同名不同内容的文件不会互相覆盖
            String mergedPath = "merged/" + fileMd5;
            logger.info("开始合并分片 => fileMd5: {}, fileName: {}, fileType: {}, 合并后路径: {}", fileMd5, fileName, fileType, mergedPath);
            
            try {
                // 合并分片
                List<ComposeSource> sources = partPaths.stream()
                        .map(path -> ComposeSource.builder().bucket("uploads").object(path).build())
                        .collect(Collectors.toList());
                
                logger.debug("构建合并请求 => fileMd5: {}, fileName: {}, targetPath: {}, sourcePaths: {}", 
                          fileMd5, fileName, mergedPath, partPaths);
                
                minioClient.composeObject(
                        ComposeObjectArgs.builder()
                                .bucket("uploads")
                                .object(mergedPath)
                                .sources(sources)
                                .build()
                );
                logger.info("分片合并成功 => fileMd5: {}, fileName: {}, fileType: {}, mergedPath: {}", fileMd5, fileName, fileType, mergedPath);
                
                // 检查合并后的文件
                StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                        .bucket("uploads")
                        .object(mergedPath)
                        .build()
                );
                logger.info("合并文件信息 => fileMd5: {}, fileName: {}, fileType: {}, path: {}, size: {}", fileMd5, fileName, fileType, mergedPath, stat.size());

                // 先用条件更新把 MERGING 改成 COMPLETED，成功后再清理分片：状态没改成功时分片还在，用户可以重新合并
                completeMerge(fileMd5, fileName, userId);
                cleanupMergedChunks(fileMd5, fileName, userId, partPaths);

                // 生成预签名 URL（有效期为 1 小时）
                logger.info("开始生成预签名URL => fileMd5: {}, fileName: {}, path: {}", fileMd5, fileName, mergedPath);
                String presignedUrl = generateMergedObjectUrl(fileMd5);
                logger.info("预签名URL已生成 => fileMd5: {}, fileName: {}, fileType: {}, URL: {}", fileMd5, fileName, fileType, presignedUrl);
                
                return presignedUrl;
            } catch (CustomException e) {
                throw e;
            } catch (Exception e) {
                logger.error("合并文件失败 => fileMd5: {}, fileName: {}, fileType: {}, 错误类型: {}, 错误信息: {}", 
                          fileMd5, fileName, fileType, e.getClass().getName(), e.getMessage(), e);
                throw new RuntimeException("合并文件失败: " + e.getMessage(), e);
            }
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            logger.error("文件合并过程中发生错误 => fileMd5: {}, fileName: {}, fileType: {}, 错误类型: {}, 错误信息: {}", 
                      fileMd5, fileName, fileType, e.getClass().getName(), e.getMessage(), e);
            throw new RuntimeException("文件合并失败: " + e.getMessage(), e);
        }
    }

    public GetObjectResponse getMergedFileStream(String fileMd5) throws Exception {
        return minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket("uploads")
                        .object("merged/" + fileMd5)
                        .build()
        );
    }

    public String generateMergedObjectUrl(String fileMd5) throws Exception {
        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket("uploads")
                        .object("merged/" + fileMd5)
                        .expiry(1, TimeUnit.HOURS)
                        .build()
        );
    }

    /**
     * 分片对象路径带上用户ID，两个用户同时上传同一个文件时各写各的分片，合并后各删各的
     */
    private String buildChunkStoragePath(String userId, String fileMd5, int chunkIndex) {
        return buildChunkPrefix(userId, fileMd5) + chunkIndex;
    }

    private String buildChunkPrefix(String userId, String fileMd5) {
        return "chunks/" + userId + "/" + fileMd5 + "/";
    }

    private void completeMerge(String fileMd5, String fileName, String userId) {
        FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId)
                .orElseThrow(() -> {
                    logger.error("更新文件状态失败，文件记录不存在 => fileMd5: {}, fileName: {}", fileMd5, fileName);
                    return new RuntimeException("文件记录不存在: " + fileMd5);
                });
        int updatedRows = fileUploadRepository.completeMergeIfMerging(
                fileUpload.getId(),
                FileUpload.STATUS_MERGING,
                FileUpload.STATUS_COMPLETED,
                LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS)
        );
        if (updatedRows == 1) {
            logger.info("文件状态已更新为已完成 => fileMd5: {}, fileName: {}, userId: {}", fileMd5, fileName, userId);
            return;
        }

        // 影响行数为 0：状态已经被其他请求或定时任务改掉。已完成按幂等成功处理，其他状态说明这次合并结果不能生效
        int currentStatus = fileUploadRepository.findById(fileUpload.getId())
                .map(FileUpload::getStatus)
                .orElseThrow(() -> new CustomException("文件记录已被清理，请重新上传", HttpStatus.CONFLICT));
        if (currentStatus == FileUpload.STATUS_COMPLETED) {
            logger.info("文件已被其他请求合并完成，按幂等成功处理 => fileMd5: {}, userId: {}", fileMd5, userId);
            return;
        }
        logger.warn("合并完成时文件状态已变化，放弃本次合并结果 => fileMd5: {}, userId: {}, currentStatus: {}",
                fileMd5, userId, currentStatus);
        throw new CustomException("文件状态已变化，请刷新后重试", HttpStatus.CONFLICT);
    }

    private void cleanupMergedChunks(String fileMd5, String fileName, String userId, List<String> partPaths) {
        logger.info("开始清理分片文件 => fileMd5: {}, fileName: {}, 分片数量: {}", fileMd5, fileName, partPaths.size());
        removeChunkObjects(partPaths, fileName);
        int deletedChunkRows = chunkInfoRepository.deleteByFileMd5AndUserId(fileMd5, userId);
        logger.info("分片元数据已清理 => fileMd5: {}, fileName: {}, deletedRows: {}", fileMd5, fileName, deletedChunkRows);
        try {
            deleteFileMark(fileMd5, userId);
        } catch (Exception e) {
            // bitmap 带过期时间，删除失败不影响合并结果
            logger.warn("删除Redis分片状态失败，等待过期 => fileMd5: {}, userId: {}, error: {}", fileMd5, userId, e.getMessage());
        }
    }

    /**
     * 清理一次被放弃的上传：分片对象（按记录和按前缀各删一遍，覆盖记录没写成功的对象）、分片记录、Redis bitmap
     */
    public void cleanupAbandonedChunks(String fileMd5, String userId) {
        List<String> paths = new ArrayList<>(chunkInfoRepository.findByFileMd5AndUserIdOrderByChunkIndexAsc(fileMd5, userId)
                .stream()
                .map(ChunkInfo::getStoragePath)
                .toList());
        try {
            for (Result<Item> result : minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket("uploads")
                    .prefix(buildChunkPrefix(userId, fileMd5))
                    .recursive(true)
                    .build())) {
                String objectName = result.get().objectName();
                if (!paths.contains(objectName)) {
                    paths.add(objectName);
                }
            }
        } catch (Exception e) {
            logger.warn("列举废弃分片对象失败，只按分片记录清理 => fileMd5: {}, userId: {}, error: {}", fileMd5, userId, e.getMessage());
        }
        removeChunkObjects(paths, "abandoned");
        int deletedRows = chunkInfoRepository.deleteByFileMd5AndUserId(fileMd5, userId);
        try {
            deleteFileMark(fileMd5, userId);
        } catch (Exception e) {
            logger.warn("删除废弃上传的Redis分片状态失败，等待过期 => fileMd5: {}, userId: {}", fileMd5, userId);
        }
        logger.info("废弃上传已清理 => fileMd5: {}, userId: {}, objects: {}, chunkRows: {}", fileMd5, userId, paths.size(), deletedRows);
    }

    private void removeChunkObjects(List<String> paths, String fileName) {
        for (String path : paths) {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder().bucket("uploads").object(path).build());
                logger.debug("分片文件已删除 => fileName: {}, path: {}", fileName, path);
            } catch (Exception e) {
                // 记录错误但不中断流程
                logger.warn("删除分片文件失败，将继续处理 => fileName: {}, path: {}, 错误: {}", fileName, path, e.getMessage());
            }
        }
    }

    private void touchUploadHeartbeat(FileUpload fileUpload) {
        if (fileUpload.getId() == null) {
            return;
        }
        try {
            fileUploadRepository.touchUploadHeartbeat(
                    fileUpload.getId(), FileUpload.STATUS_UPLOADING, LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS));
        } catch (Exception e) {
            // 心跳只影响废弃上传的判断，失败时最坏是按上一次心跳时间判断
            logger.warn("刷新上传心跳失败 => fileMd5: {}, userId: {}, error: {}", fileUpload.getFileMd5(), fileUpload.getUserId(), e.getMessage());
        }
    }

    private boolean chunkObjectExists(String storagePath, String fileMd5, String fileName, int chunkIndex) {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket("uploads")
                            .object(storagePath)
                            .build()
            );
            return true;
        } catch (Exception e) {
            logger.warn("分片元数据存在但 MinIO 对象不可用，将重新上传分片 => fileMd5: {}, fileName: {}, chunkIndex: {}, path: {}, error: {}",
                    fileMd5, fileName, chunkIndex, storagePath, e.getMessage());
            return false;
        }
    }

    private void clearStaleChunkState(String fileMd5, int chunkIndex, String userId, String fileName, String storagePath) {
        if (!chunkObjectExists(storagePath, fileMd5, fileName, chunkIndex)) {
            int deletedRows = chunkInfoRepository.deleteByFileMd5AndUserIdAndChunkIndex(fileMd5, userId, chunkIndex);
            clearChunkUploadedQuietly(fileMd5, chunkIndex, userId, fileName);
            logger.warn("已清理失效分片状态 => fileMd5: {}, fileName: {}, chunkIndex: {}, deletedRows: {}",
                    fileMd5, fileName, chunkIndex, deletedRows);
            return;
        }

        if (!chunkInfoRepository.existsByFileMd5AndUserIdAndChunkIndex(fileMd5, userId, chunkIndex)) {
            clearChunkUploadedQuietly(fileMd5, chunkIndex, userId, fileName);
            logger.warn("Redis 标记存在但数据库分片元数据缺失，将重新上传分片 => fileMd5: {}, fileName: {}, chunkIndex: {}",
                    fileMd5, fileName, chunkIndex);
        }
    }

    private void clearChunkUploadedQuietly(String fileMd5, int chunkIndex, String userId, String fileName) {
        try {
            String redisKey = "upload:" + userId + ":" + fileMd5;
            redisTemplate.opsForValue().setBit(redisKey, chunkIndex, false);
        } catch (Exception e) {
            logger.warn("清理 Redis 分片标记失败，将继续重新上传分片 => fileMd5: {}, fileName: {}, chunkIndex: {}, error: {}",
                    fileMd5, fileName, chunkIndex, e.getMessage());
        }
    }

    /**
     * 转换为公开 URL
     * @param minioUrl
     * @return
     */
    public String transToPublicUrl(String minioUrl) {
        if (StringUtils.isBlank(minioUrl) || Objects.equals(minioConfig.getEndpoint(), minioConfig.getPublicUrl())) {
            return minioUrl;
        }
        return minioUrl.replaceFirst(minioConfig.getEndpoint(), minioConfig.getPublicUrl());
    }

    private FileUpload getOrCreateFileUpload(String fileMd5,
                                             long totalSize,
                                             String fileName,
                                             String orgTag,
                                             boolean isPublic,
                                             String userId,
                                             String fileType) {
        Optional<FileUpload> existingFileUpload = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId);
        if (existingFileUpload.isPresent()) {
            return existingFileUpload.get();
        }

        String lockKey = userId + ":" + fileMd5;
        Object createLock = FILE_UPLOAD_CREATE_LOCKS.computeIfAbsent(lockKey, ignored -> new Object());
        synchronized (createLock) {
            try {
                existingFileUpload = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId);
                if (existingFileUpload.isPresent()) {
                    return existingFileUpload.get();
                }

                logger.info("创建新的文件记录 => fileMd5: {}, fileName: {}, fileType: {}, totalSize: {}, userId: {}, orgTag: {}, isPublic: {}",
                        fileMd5, fileName, fileType, totalSize, userId, orgTag, isPublic);

                FileUpload fileUpload = new FileUpload();
                fileUpload.setFileMd5(fileMd5);
                fileUpload.setFileName(fileName);
                fileUpload.setTotalSize(totalSize);
                fileUpload.setStatus(FileUpload.STATUS_UPLOADING);
                fileUpload.setUserId(userId);
                fileUpload.setOrgTag(orgTag);
                fileUpload.setPublic(isPublic);
                fileUpload.setVectorizationStatus(null);
                fileUpload.setVectorizationErrorMessage(null);

                try {
                    return fileUploadRepository.save(fileUpload);
                } catch (DataIntegrityViolationException e) {
                    logger.info("文件记录已存在，按幂等成功处理 => fileMd5: {}, userId: {}", fileMd5, userId);
                    return fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId)
                            .orElseThrow(() -> new RuntimeException("文件记录并发创建后查询失败", e));
                } catch (Exception e) {
                    logger.error("创建文件记录失败 => fileMd5: {}, fileName: {}, fileType: {}, 错误: {}", fileMd5, fileName, fileType, e.getMessage(), e);
                    throw new RuntimeException("创建文件记录失败: " + e.getMessage(), e);
                }
            } finally {
                FILE_UPLOAD_CREATE_LOCKS.remove(lockKey, createLock);
            }
        }
    }
}
