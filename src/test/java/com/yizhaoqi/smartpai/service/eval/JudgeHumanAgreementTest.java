package com.yizhaoqi.smartpai.service.eval;

import com.yizhaoqi.smartpai.config.RagEvalProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeHumanAgreementTest {

    private final RagEvalProperties.HumanAgreement config = new RagEvalProperties.HumanAgreement();

    @Test
    void computesAgreementKappaAndFlagsRecalibration() {
        // 评委判对 6 条，人工判对 5 条，一致 7 条：po = 0.7，pe = 0.6*0.5 + 0.4*0.5 = 0.5，κ = 0.4
        boolean[] judge = {true, true, true, true, true, true, false, false, false, false};
        boolean[] human = {true, true, true, true, false, false, false, false, false, true};
        List<JudgeHumanAgreement.JudgeLabel> labels = new ArrayList<>();
        Map<String, HumanVerdict> verdicts = new HashMap<>();
        for (int i = 0; i < judge.length; i++) {
            labels.add(new JudgeHumanAgreement.JudgeLabel("c" + i, judge[i], judge[i] ? 5 : 1));
            verdicts.put("c" + i, new HumanVerdict("c" + i, human[i], human[i] ? 4 : 1, null, "admin", null));
        }

        JudgeHumanAgreement agreement = JudgeHumanAgreement.compute(labels, verdicts, config);

        assertEquals(10, agreement.comparableCases());
        assertEquals(7, agreement.agreedCases());
        assertEquals(0.7, agreement.agreementRate(), 1e-9);
        assertEquals(0.4, agreement.cohensKappa(), 1e-9);
        assertEquals(0.6, agreement.judgeCorrectRate(), 1e-9);
        assertEquals(0.5, agreement.humanCorrectRate(), 1e-9);
        assertEquals(List.of("c4", "c5", "c9"), agreement.disagreementCaseIds());
        assertEquals(JudgeHumanAgreement.Status.NEEDS_RECALIBRATION, agreement.status());
        assertTrue(agreement.warning().contains("70.0%") && agreement.warning().contains("重新校准"));
        // 分数差：4 条 5 vs 4、2 条 5 vs 1、3 条 1 vs 1、1 条 1 vs 4 → (4 + 8 + 0 + 3) / 10
        assertEquals(1.5, agreement.meanAbsoluteScoreDiff(), 1e-9);
    }

    @Test
    void highAgreementIsOkButOptionalKappaFloorCanStillFlag() {
        List<JudgeHumanAgreement.JudgeLabel> labels = new ArrayList<>();
        Map<String, HumanVerdict> verdicts = new HashMap<>();
        // 9 条都判对且一致，1 条评委判对人工判错：一致率 90%，但正确率偏高导致 κ = 0
        for (int i = 0; i < 10; i++) {
            labels.add(new JudgeHumanAgreement.JudgeLabel("c" + i, true, 5));
            verdicts.put("c" + i, new HumanVerdict("c" + i, i != 9, null, null, "admin", null));
        }

        JudgeHumanAgreement ok = JudgeHumanAgreement.compute(labels, verdicts, config);
        assertEquals(JudgeHumanAgreement.Status.OK, ok.status());
        assertEquals(0.0, ok.cohensKappa(), 1e-9);
        assertNull(ok.warning());
        assertNull(ok.meanAbsoluteScoreDiff(), "人工没打分时不算分数差");

        config.setMinKappa(0.4);
        JudgeHumanAgreement flagged = JudgeHumanAgreement.compute(labels, verdicts, config);
        assertEquals(JudgeHumanAgreement.Status.NEEDS_RECALIBRATION, flagged.status());
        assertTrue(flagged.warning().contains("kappa 0.00"));
    }

    @Test
    void kappaUndefinedWhenBothSidesUseOneLabel() {
        assertNull(JudgeHumanAgreement.kappa(5, 5, 5, 5));
    }

    @Test
    void smallSamplesAndJudgeFailuresAreReportedSeparately() {
        List<JudgeHumanAgreement.JudgeLabel> labels = List.of(
                new JudgeHumanAgreement.JudgeLabel("a", false, 1),
                new JudgeHumanAgreement.JudgeLabel("b", null, null),
                new JudgeHumanAgreement.JudgeLabel("c", true, 5));
        Map<String, HumanVerdict> verdicts = Map.of(
                "a", new HumanVerdict("a", true, null, null, "admin", null),
                "b", new HumanVerdict("b", true, null, "评委输出解析失败，人工补判", "admin", null));

        JudgeHumanAgreement agreement = JudgeHumanAgreement.compute(labels, verdicts, config);

        assertEquals(2, agreement.reviewedCases());
        assertEquals(1, agreement.comparableCases());
        assertEquals(1, agreement.humanOnlyCases(), "评委失败的用例只有人工结论，不参与一致率");
        assertEquals(JudgeHumanAgreement.Status.INSUFFICIENT_SAMPLE, agreement.status());
        assertTrue(agreement.warning().contains("至少需要 5 条"));

        assertEquals(JudgeHumanAgreement.Status.NO_REVIEWS,
                JudgeHumanAgreement.compute(labels, Map.of(), config).status());
    }
}
