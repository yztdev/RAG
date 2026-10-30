package com.yizhaoqi.smartpai.service.eval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalDiagnosisAndSamplingTest {

    private static final JudgeVerdict CORRECT = JudgeVerdict.of(5, true, "ok");
    private static final JudgeVerdict WRONG = JudgeVerdict.of(2, false, "wrong");
    private static final JudgeVerdict BROKEN = JudgeVerdict.failed("bad json", "???");

    @Test
    void retrievalHitAndCorrectAnswerIsOk() {
        assertEquals(EvalDiagnosis.OK, EvalDiagnosis.classify(true, true, CORRECT));
    }

    @Test
    void retrievalHitButWrongAnswerPointsToPromptOrModel() {
        assertEquals(EvalDiagnosis.PROMPT_OR_MODEL, EvalDiagnosis.classify(true, true, WRONG));
    }

    @Test
    void retrievalMissPointsToChunkingAndRetrieval() {
        assertEquals(EvalDiagnosis.RETRIEVAL_MISS, EvalDiagnosis.classify(false, true, WRONG));
        // 检索没命中时，生成或评委失败也先归到检索问题
        assertEquals(EvalDiagnosis.RETRIEVAL_MISS, EvalDiagnosis.classify(false, false, null));
        assertEquals(EvalDiagnosis.RETRIEVAL_MISS, EvalDiagnosis.classify(false, true, BROKEN));
    }

    @Test
    void retrievalMissButCorrectAnswerIsFlaggedSeparately() {
        assertEquals(EvalDiagnosis.MISS_BUT_CORRECT, EvalDiagnosis.classify(false, true, CORRECT));
    }

    @Test
    void failuresAndUnlabeledCases() {
        assertEquals(EvalDiagnosis.GENERATION_FAILED, EvalDiagnosis.classify(true, false, null));
        assertEquals(EvalDiagnosis.JUDGE_FAILED, EvalDiagnosis.classify(true, true, BROKEN));
        assertEquals(EvalDiagnosis.JUDGE_FAILED, EvalDiagnosis.classify(null, true, null));
        assertEquals(EvalDiagnosis.NOT_LABELED, EvalDiagnosis.classify(null, true, WRONG));
    }

    @Test
    void judgeFailureAlwaysNeedsReview() {
        HumanReviewSampler sampler = new HumanReviewSampler(0d, new Random(1));
        for (int i = 0; i < 50; i++) {
            assertNull(sampler.decide(false));
            assertEquals(HumanReviewSampler.REASON_JUDGE_FAILED, sampler.decide(true));
        }
    }

    @Test
    void fullSampleRateReviewsEverything() {
        HumanReviewSampler sampler = new HumanReviewSampler(1d, new Random(1));
        for (int i = 0; i < 50; i++) {
            assertEquals(HumanReviewSampler.REASON_SAMPLED, sampler.decide(false));
        }
    }

    @Test
    void sampleRateIsApproximatelyRespectedAndReproducibleWithSeed() {
        List<String> first = decisions(HumanReviewSampler.of(0.1, 42L), 2000);
        List<String> second = decisions(HumanReviewSampler.of(0.1, 42L), 2000);
        assertEquals(first, second);

        long sampled = first.stream().filter(HumanReviewSampler.REASON_SAMPLED::equals).count();
        assertTrue(sampled > 140 && sampled < 260, "sampled=" + sampled);
    }

    @Test
    void sampleRateIsClamped() {
        assertNull(new HumanReviewSampler(-1d, new Random(1)).decide(false));
        assertEquals(HumanReviewSampler.REASON_SAMPLED, new HumanReviewSampler(5d, new Random(1)).decide(false));
    }

    private static List<String> decisions(HumanReviewSampler sampler, int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(sampler.decide(false));
        }
        return out;
    }
}
