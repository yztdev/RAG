package com.yizhaoqi.smartpai.service.conflict;

/**
 * 文件向量化完成（首次入库或重试 / 重建单个文档）后发布，供冲突检测等后置任务订阅
 */
public record DocumentVectorizedEvent(String fileMd5) {
}
