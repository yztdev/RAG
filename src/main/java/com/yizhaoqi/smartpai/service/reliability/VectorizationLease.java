package com.yizhaoqi.smartpai.service.reliability;

import java.time.LocalDateTime;

/**
 * 一次向量化任务的认领凭证。claimedAt 是数据库里存下的认领时间，心跳和完成都用它做条件更新
 */
public record VectorizationLease(Long fileUploadId, String fileMd5, LocalDateTime claimedAt) {
}
