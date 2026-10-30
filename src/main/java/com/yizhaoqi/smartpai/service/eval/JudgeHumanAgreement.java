package com.yizhaoqi.smartpai.service.eval;

import com.yizhaoqi.smartpai.config.RagEvalProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 评委模型与人工判定的一致性。只比较评委和人工都给出了 correct 的用例：
 * <ul>
 *     <li>agreementRate：两边 correct 结论相同的比例；</li>
 *     <li>cohensKappa：扣掉"碰巧一致"后的一致性，κ = (po - pe) / (1 - pe)，pe 由两边各自的判对比例算出；
 *     两边全判同一类时 pe = 1，κ 无定义，记为 null；</li>
 *     <li>meanAbsoluteScoreDiff：人工也打了 1-5 分的用例上，评委分与人工分的平均绝对差。</li>
 * </ul>
 * 一致率（或配置了下限的 κ）低于阈值时 status = NEEDS_RECALIBRATION，说明评分标准要改，或者评委模型已经不可靠。
 */
public record JudgeHumanAgreement(
        Status status,
        String warning,
        int reviewedCases,
        int comparableCases,
        int agreedCases,
        Double agreementRate,
        Double cohensKappa,
        Double judgeCorrectRate,
        Double humanCorrectRate,
        int scoredCases,
        Double meanAbsoluteScoreDiff,
        int humanOnlyCases,
        List<String> disagreementCaseIds,
        double minAgreementRate,
        Double minKappa,
        int minComparableCases
) {

    private static final int MAX_LISTED_DISAGREEMENTS = 50;

    public enum Status {
        /** 还没有人工判定 */
        NO_REVIEWS,
        /** 可比较的用例太少，不下结论 */
        INSUFFICIENT_SAMPLE,
        OK,
        /** 评委与人工一致性低于阈值，评分标准或评委模型需要重新校准 */
        NEEDS_RECALIBRATION
    }

    /**
     * 报告里单条用例的评委结论（评委调用失败或输出解析失败时 judgeCorrect 为 null）
     */
    public record JudgeLabel(String caseId, Boolean judgeCorrect, Integer judgeScore) {
    }

    public static JudgeHumanAgreement compute(List<JudgeLabel> judgeLabels,
                                              Map<String, HumanVerdict> humanVerdicts,
                                              RagEvalProperties.HumanAgreement config) {
        int comparable = 0;
        int agreed = 0;
        int judgeCorrect = 0;
        int humanCorrect = 0;
        int scored = 0;
        int scoreDiffSum = 0;
        int humanOnly = 0;
        List<String> disagreements = new ArrayList<>();
        for (JudgeLabel label : judgeLabels) {
            HumanVerdict human = humanVerdicts.get(label.caseId());
            if (human == null || human.correct() == null) {
                continue;
            }
            if (label.judgeCorrect() == null) {
                humanOnly++;
                continue;
            }
            comparable++;
            boolean j = label.judgeCorrect();
            boolean h = human.correct();
            judgeCorrect += j ? 1 : 0;
            humanCorrect += h ? 1 : 0;
            if (j == h) {
                agreed++;
            } else if (disagreements.size() < MAX_LISTED_DISAGREEMENTS) {
                disagreements.add(label.caseId());
            }
            if (human.score() != null && label.judgeScore() != null) {
                scored++;
                scoreDiffSum += Math.abs(human.score() - label.judgeScore());
            }
        }

        Double agreementRate = ratio(agreed, comparable);
        Double kappa = comparable == 0 ? null : kappa(comparable, agreed, judgeCorrect, humanCorrect);
        Double meanScoreDiff = scored == 0 ? null : (double) scoreDiffSum / scored;
        Status status = status(humanVerdicts.size(), comparable, agreementRate, kappa, config);
        return new JudgeHumanAgreement(status, warning(status, comparable, agreementRate, kappa, config),
                humanVerdicts.size(), comparable, agreed, agreementRate, kappa,
                ratio(judgeCorrect, comparable), ratio(humanCorrect, comparable),
                scored, meanScoreDiff, humanOnly, List.copyOf(disagreements),
                config.getMinAgreementRate(), config.getMinKappa(), config.getMinComparableCases());
    }

    static Double kappa(int n, int agreed, int judgeCorrect, int humanCorrect) {
        double po = (double) agreed / n;
        double pJudge = (double) judgeCorrect / n;
        double pHuman = (double) humanCorrect / n;
        double pe = pJudge * pHuman + (1 - pJudge) * (1 - pHuman);
        if (Math.abs(1 - pe) < 1e-12) {
            return null;
        }
        return (po - pe) / (1 - pe);
    }

    private static Status status(int reviewed, int comparable, Double agreementRate, Double kappa,
                                 RagEvalProperties.HumanAgreement config) {
        if (reviewed == 0) {
            return Status.NO_REVIEWS;
        }
        if (comparable < Math.max(config.getMinComparableCases(), 1)) {
            return Status.INSUFFICIENT_SAMPLE;
        }
        boolean lowAgreement = agreementRate != null && agreementRate < config.getMinAgreementRate();
        boolean lowKappa = config.getMinKappa() != null && kappa != null && kappa < config.getMinKappa();
        return lowAgreement || lowKappa ? Status.NEEDS_RECALIBRATION : Status.OK;
    }

    private static String warning(Status status, int comparable, Double agreementRate, Double kappa,
                                  RagEvalProperties.HumanAgreement config) {
        return switch (status) {
            case NO_REVIEWS, OK -> null;
            case INSUFFICIENT_SAMPLE -> String.format(Locale.ROOT,
                    "评委和人工都给出结论的用例只有 %d 条（至少需要 %d 条），一致率暂不下结论",
                    comparable, config.getMinComparableCases());
            case NEEDS_RECALIBRATION -> String.format(Locale.ROOT,
                    "评委与人工一致率 %s（阈值 %s），Cohen's kappa %s%s。评分标准或评委模型需要重新校准，"
                            + "本次评测的准确率和平均分不能直接采信，先看 disagreementCaseIds 里的分歧用例",
                    percent(agreementRate), percent(config.getMinAgreementRate()),
                    kappa == null ? "无定义" : String.format(Locale.ROOT, "%.2f", kappa),
                    config.getMinKappa() == null ? "" : String.format(Locale.ROOT, "（下限 %.2f）", config.getMinKappa()));
        };
    }

    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private static String percent(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }
}
