package com.yizhaoqi.smartpai.service.conflict;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键值差异判定（纯规则，结果确定）：
 * 1. 归一化（NFKC 全半角统一、小写、去空白和标点）后完全相同的两段文字视为重复内容，不是冲突；
 * 2. 把日期、版本号、数字替换成占位符，比较剩余文字的相似度，要足够高才说明两段讲的是同一件事；
 * 3. 两段各自抽出的关键值集合都不为空且不相等，才判为关键值不同。
 */
public final class KeyValueConflictAnalyzer {

    // 占位符用 Unicode 私有区字符，不属于标点 / 符号，归一化时不会被删掉
    private static final String DATE_MARK = "\uE000";
    private static final String VERSION_MARK = "\uE001";
    private static final String NUMBER_MARK = "\uE002";

    private static final Pattern THOUSANDS_SEPARATOR = Pattern.compile("(?<=\\d),(?=\\d{3}(?!\\d))");
    private static final Pattern DATE = Pattern.compile(
            "(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})(?:\\s*[-/.月]\\s*(\\d{1,2})\\s*日?)?(?!\\d)");
    // 三段及以上的点分版本号，或以 v 开头的版本号；"3.5" 这类两段数字按小数处理
    private static final Pattern VERSION = Pattern.compile(
            "(?<!\\w|\\d\\.)(?:v(\\d+(?:\\.\\d+)*)|(\\d+(?:\\.\\d+){2,}))(?!\\w|\\.\\d)");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern NOISE = Pattern.compile("[\\s\\p{P}\\p{S}]+");

    private KeyValueConflictAnalyzer() {
    }

    public record Analysis(boolean conflict,
                           String reason,
                           double maskedSimilarity,
                           Set<String> onlyInFirst,
                           Set<String> onlyInSecond) {
    }

    record Extraction(String maskedText, Set<String> values) {
    }

    /**
     * @param first                  新文件分块
     * @param second                 已有文件分块
     * @param maskedSimilarityFloor  屏蔽关键值后的文本相似度下限
     */
    public static Analysis analyze(String first, String second, double maskedSimilarityFloor) {
        if (isBlank(first) || isBlank(second)) {
            return rejected("文本为空", 0d);
        }
        if (normalize(first).equals(normalize(second))) {
            return rejected("归一化后文本相同，属于重复内容", 1d);
        }

        Extraction a = extract(first);
        Extraction b = extract(second);
        double maskedSimilarity = diceSimilarity(normalize(a.maskedText()), normalize(b.maskedText()));
        if (maskedSimilarity < maskedSimilarityFloor) {
            return rejected("屏蔽关键值后文本差异较大，讲的不是同一件事", maskedSimilarity);
        }
        if (a.values().isEmpty() || b.values().isEmpty()) {
            return rejected("至少一侧没有可比较的关键值", maskedSimilarity);
        }
        if (a.values().equals(b.values())) {
            return rejected("关键值一致", maskedSimilarity);
        }

        Set<String> onlyInFirst = new LinkedHashSet<>(a.values());
        onlyInFirst.removeAll(b.values());
        Set<String> onlyInSecond = new LinkedHashSet<>(b.values());
        onlyInSecond.removeAll(a.values());
        return new Analysis(true, "文本高度相似但关键值不同", maskedSimilarity,
                Collections.unmodifiableSet(onlyInFirst), Collections.unmodifiableSet(onlyInSecond));
    }

    /**
     * NFKC（全角转半角）+ 小写 + 去掉空白、标点和符号
     */
    static String normalize(String text) {
        String nfkc = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return NOISE.matcher(nfkc).replaceAll("");
    }

    /**
     * 依次抽取日期、版本号、数字，并在文本中替换成占位符。关键值统一成规范形式（日期 yyyy-m-d，数字去掉多余的 0）。
     */
    static Extraction extract(String text) {
        String working = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        working = THOUSANDS_SEPARATOR.matcher(working).replaceAll("");
        Set<String> values = new LinkedHashSet<>();

        working = replace(DATE, working, DATE_MARK, m -> {
            StringBuilder date = new StringBuilder(m.group(1)).append('-').append(Integer.parseInt(m.group(2)));
            if (m.group(3) != null) {
                date.append('-').append(Integer.parseInt(m.group(3)));
            }
            return "date:" + date;
        }, values);
        working = replace(VERSION, working, VERSION_MARK,
                m -> "version:" + (m.group(1) != null ? m.group(1) : m.group(2)), values);
        working = replace(NUMBER, working, NUMBER_MARK,
                m -> "number:" + new BigDecimal(m.group()).stripTrailingZeros().toPlainString(), values);
        return new Extraction(working, Collections.unmodifiableSet(values));
    }

    private static String replace(Pattern pattern, String input, String mark,
                                  java.util.function.Function<Matcher, String> canonical, Set<String> sink) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            sink.add(canonical.apply(matcher));
            matcher.appendReplacement(out, mark);
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 字符二元组的 Dice 系数，中英文都适用，不依赖分词
     */
    static double diceSimilarity(String a, String b) {
        if (a.equals(b)) {
            return 1d;
        }
        if (a.length() < 2 || b.length() < 2) {
            return 0d;
        }
        Map<String, Integer> bigramsA = bigrams(a);
        Map<String, Integer> bigramsB = bigrams(b);
        int overlap = 0;
        for (Map.Entry<String, Integer> entry : bigramsA.entrySet()) {
            overlap += Math.min(entry.getValue(), bigramsB.getOrDefault(entry.getKey(), 0));
        }
        return 2d * overlap / ((a.length() - 1) + (b.length() - 1));
    }

    private static Map<String, Integer> bigrams(String text) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < text.length() - 1; i++) {
            counts.merge(text.substring(i, i + 2), 1, Integer::sum);
        }
        return counts;
    }

    private static Analysis rejected(String reason, double maskedSimilarity) {
        return new Analysis(false, reason, maskedSimilarity, Set.of(), Set.of());
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
