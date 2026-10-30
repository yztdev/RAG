package com.yizhaoqi.smartpai.exception;

/**
 * 用户 Token 余额不足。和短时限流不同，等几秒再试结果不会变，后台任务遇到它应直接失败，不做重试
 */
public class InsufficientBalanceException extends RateLimitExceededException {

    public InsufficientBalanceException(String message) {
        super(message, 0);
    }
}
