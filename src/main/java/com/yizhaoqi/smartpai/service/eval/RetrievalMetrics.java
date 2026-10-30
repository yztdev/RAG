package com.yizhaoqi.smartpai.service.eval;

import com.yizhaoqi.smartpai.entity.SearchResult;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检索指标：
 * - Recall@K（本项目口径）：前 K 条结果里只要出现任一相关分块即记为命中，数据集上取命中率；
 * - 倒数排名 RR：第一条相关分块排在第 r 位时为 1/r，前 K 条都没有时为 0，数据集上取平均即 MRR；
 * - 期望文档覆盖率：前 K 条覆盖了多少比例的期望文档（一道题需要多篇文档时参考）。
 * 期望文档优先按 MD5 匹配，其次按文件名（忽略大小写）匹配。
 * 用例标注了 expectedEvidence 时，相关分块还要求分块文本包含其中任意一个短语（分块级）；
 * 否则同一文件的任何分块都算相关（文件级）。文件级命中另外单独记录，用来对比两种口径的差距。
 * - NDCG@K（二元相关）：DCG = Σ rel_i / log2(i+1)，理想排序取候选池里的相关条数，衡量精排把相关分块排到前面的程度。
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /**
     * @return 第一条相关结果的排名（从 1 开始），前 K 条都不相关时返回 0
     */
    public static int firstRelevantRank(List<SearchResult> results, EvalCase evalCase, int k) {
        Set<String> md5s = lower(evalCase.expectedFileMd5s());
        Set<String> names = lower(evalCase.expectedFileNames());
        List<String> evidence = normalizedEvidence(evalCase);
        int limit = limit(results, k);
        for (int i = 0; i < limit; i++) {
            SearchResult result = results.get(i);
            if (isDocRelevant(result, md5s, names) && containsEvidence(result, evidence)) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * 只按文件判断的命中：前 K 条里出现任一期望文件的分块即算命中
     */
    public static boolean docHit(List<SearchResult> results, EvalCase evalCase, int k) {
        Set<String> md5s = lower(evalCase.expectedFileMd5s());
        Set<String> names = lower(evalCase.expectedFileNames());
        int limit = limit(results, k);
        for (int i = 0; i < limit; i++) {
            if (isDocRelevant(results.get(i), md5s, names)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 列表里相关分块的条数
     */
    public static int countRelevant(List<SearchResult> results, EvalCase evalCase) {
        if (results == null) {
            return 0;
        }
        return (int) results.stream().filter(result -> isRelevant(result, evalCase)).count();
    }

    /**
     * 二元相关的 NDCG@K。idealRelevant 是理想排序里能放进前 K 的相关条数（取候选池里的相关条数）；为 0 时返回 null
     */
    public static Double ndcg(List<SearchResult> results, EvalCase evalCase, int k, int idealRelevant) {
        if (idealRelevant <= 0) {
            return null;
        }
        double dcg = 0d;
        int limit = limit(results, k);
        for (int i = 0; i < limit; i++) {
            if (isRelevant(results.get(i), evalCase)) {
                dcg += 1d / log2(i + 2);
            }
        }
        double idcg = 0d;
        for (int i = 0; i < Math.min(k, idealRelevant); i++) {
            idcg += 1d / log2(i + 2);
        }
        return dcg / idcg;
    }

    private static double log2(int value) {
        return Math.log(value) / Math.log(2);
    }

    public static double reciprocalRank(int rank) {
        return rank > 0 ? 1d / rank : 0d;
    }

    /**
     * 前 K 条结果覆盖的期望文档比例；一个期望项（MD5 或文件名）被任一结果命中即算覆盖
     */
    public static double expectedDocCoverage(List<SearchResult> results, EvalCase evalCase, int k) {
        int expectedCount = evalCase.expectedFileMd5s().size() + evalCase.expectedFileNames().size();
        if (expectedCount == 0) {
            return 0d;
        }
        List<SearchResult> top = results == null ? List.of() : results.subList(0, limit(results, k));
        Set<String> retrievedMd5s = top.stream().map(SearchResult::getFileMd5).map(RetrievalMetrics::lower).collect(Collectors.toSet());
        Set<String> retrievedNames = top.stream().map(SearchResult::getFileName).map(RetrievalMetrics::lower).collect(Collectors.toSet());
        long covered = evalCase.expectedFileMd5s().stream().map(RetrievalMetrics::lower).filter(retrievedMd5s::contains).count()
                + evalCase.expectedFileNames().stream().map(RetrievalMetrics::lower).filter(retrievedNames::contains).count();
        return (double) covered / expectedCount;
    }

    public static boolean isRelevant(SearchResult result, EvalCase evalCase) {
        return isDocRelevant(result, lower(evalCase.expectedFileMd5s()), lower(evalCase.expectedFileNames()))
                && containsEvidence(result, normalizedEvidence(evalCase));
    }

    static boolean isDocRelevant(SearchResult result, Set<String> expectedMd5s, Set<String> expectedNames) {
        String md5 = lower(result.getFileMd5());
        String name = lower(result.getFileName());
        return (md5 != null && expectedMd5s.contains(md5)) || (name != null && expectedNames.contains(name));
    }

    private static boolean containsEvidence(SearchResult result, List<String> evidence) {
        if (evidence.isEmpty()) {
            return true;
        }
        String text = normalizeText(result.getTextContent());
        return evidence.stream().anyMatch(text::contains);
    }

    private static List<String> normalizedEvidence(EvalCase evalCase) {
        return evalCase.expectedEvidence().stream()
                .map(RetrievalMetrics::normalizeText)
                .filter(phrase -> !phrase.isEmpty())
                .toList();
    }

    /**
     * NFKC 把 PDF 抽取出的康熙部首（如 ⼤）、全角字符还原成常规字符，再去掉空白、统一小写，避免排版差异导致匹配不上
     */
    static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static int limit(List<SearchResult> results, int k) {
        return Math.min(results == null ? 0 : results.size(), Math.max(k, 0));
    }

    private static Set<String> lower(List<String> values) {
        Set<String> out = new HashSet<>();
        for (String value : values) {
            out.add(lower(value));
        }
        return out;
    }

    private static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
