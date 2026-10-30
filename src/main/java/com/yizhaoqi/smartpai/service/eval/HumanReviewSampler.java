package com.yizhaoqi.smartpai.service.eval;

import java.util.Random;

/**
 * 人工复核抽样：评委也会有偏差，按比例随机抽一部分用例给人工复核，用来校准评委打分；
 * 评委调用失败或输出无法解析的用例一律需要人工判断；
 * 检索没召回答案分块、评委却判对的用例也一律复核：人工校准发现模型在没看到证据时常漏掉核心要点，评委在这类用例上偏宽松。
 */
public final class HumanReviewSampler {

    public static final String REASON_JUDGE_FAILED = "JUDGE_FAILED";
    public static final String REASON_SAMPLED = "RANDOM_SAMPLE";
    public static final String REASON_MISS_BUT_CORRECT = "MISS_BUT_CORRECT";

    private final double sampleRate;
    private final Random random;

    public HumanReviewSampler(double sampleRate, Random random) {
        this.sampleRate = Math.min(Math.max(sampleRate, 0d), 1d);
        this.random = random;
    }

    public static HumanReviewSampler of(double sampleRate, Long seed) {
        return new HumanReviewSampler(sampleRate, seed == null ? new Random() : new Random(seed));
    }

    /**
     * @return 需要复核的原因；不需要复核时返回 null
     */
    public String decide(boolean judgeFailed) {
        return decide(judgeFailed, false);
    }

    /**
     * @param missButCorrect 检索未命中答案分块但评委判对
     * @return 需要复核的原因；不需要复核时返回 null
     */
    public synchronized String decide(boolean judgeFailed, boolean missButCorrect) {
        // 先消耗一次随机数，保证固定种子下同一个用例序号的抽样结果不受前面用例评委成败的影响
        boolean sampled = random.nextDouble() < sampleRate;
        if (judgeFailed) {
            return REASON_JUDGE_FAILED;
        }
        if (missButCorrect) {
            return REASON_MISS_BUT_CORRECT;
        }
        return sampled ? REASON_SAMPLED : null;
    }
}
