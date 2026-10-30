package com.yizhaoqi.smartpai.service.eval;

/**
 * 评委模型的结论。parseFailed 为 true 时 score / correct 为空，rawOutput 保留原始输出供人工复核。
 */
public record JudgeVerdict(Integer score, Boolean correct, String reason, boolean parseFailed, String rawOutput) {

    public static JudgeVerdict of(int score, boolean correct, String reason) {
        return new JudgeVerdict(score, correct, reason, false, null);
    }

    public static JudgeVerdict failed(String error, String rawOutput) {
        return new JudgeVerdict(null, null, error, true, rawOutput);
    }
}
