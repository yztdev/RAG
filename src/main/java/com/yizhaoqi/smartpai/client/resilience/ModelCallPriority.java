package com.yizhaoqi.smartpai.client.resilience;

/**
 * 模型调用优先级：在线问答优先于后台任务（上传向量化、上下文生成、冲突确认、离线评测）
 */
public enum ModelCallPriority {
    ONLINE,
    BACKGROUND
}
