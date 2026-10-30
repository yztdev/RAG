package com.yizhaoqi.smartpai.service.reliability;

/**
 * 向量化任务的认领已失效：心跳超时后被定时任务改回待处理，或被用户重新发起。
 * 当前消费者应停止处理，不写失败状态，交给新的认领者
 */
public class VectorizationLeaseLostException extends RuntimeException {

    public VectorizationLeaseLostException(String fileMd5) {
        super("向量化任务认领已失效，停止处理: fileMd5=" + fileMd5);
    }
}
