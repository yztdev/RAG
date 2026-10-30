package com.yizhaoqi.smartpai.service.citation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从回答中抽取"被引用的句子 + 引用编号"。
 * 引用格式由系统提示词约定：(来源#1: a.pdf | 第3页) 或一个括号里多个来源 (来源#1: a.pdf; 来源#2: b.pdf)。
 * 引用可能写在句号前也可能写在句号后，两种都归到它前面那句话。
 */
public final class CitationExtractor {

    private static final Pattern CITATION_GROUP = Pattern.compile("[(（][^()（）\\n]*?来源#\\d+[^()（）\\n]*[)）]");
    private static final Pattern REFERENCE_NUMBER = Pattern.compile("来源#(\\d+)");
    private static final Pattern BARE_CITATION = Pattern.compile("来源#\\d+(?::[^，。；;！？!?\\n]*)?");
    private static final Pattern LEADING_NOISE = Pattern.compile("^[\\s，,、：:；;。）)\\]]+|^(?:[-*•>#]+|\\d+[.、)])\\s*");
    private static final String SENTENCE_TERMINATORS = "。！？!?；;";

    private CitationExtractor() {
    }

    public static List<CitedClaim> extract(String answer, int maxClaimChars) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        List<Span> spans = findCitationSpans(answer);
        Map<String, Set<Integer>> claims = new LinkedHashMap<>();
        String previousClaim = null;
        int previousEnd = 0;
        for (Span span : spans) {
            String claim = claimBefore(answer, span.start(), previousEnd, maxClaimChars);
            if (claim.isEmpty()) {
                // "……。(来源#1)(来源#2)" 这类紧挨着的引用归到同一句话
                if (previousClaim != null && answer.substring(previousEnd, span.start()).isBlank()) {
                    claims.get(previousClaim).addAll(span.references());
                }
            } else {
                claims.computeIfAbsent(claim, ignored -> new LinkedHashSet<>()).addAll(span.references());
                previousClaim = claim;
            }
            previousEnd = span.end();
        }
        List<CitedClaim> result = new ArrayList<>(claims.size());
        claims.forEach((claim, refs) -> result.add(new CitedClaim(claim, List.copyOf(refs))));
        return List.copyOf(result);
    }

    private static List<Span> findCitationSpans(String answer) {
        List<Span> spans = new ArrayList<>();
        Matcher group = CITATION_GROUP.matcher(answer);
        while (group.find()) {
            spans.add(new Span(group.start(), group.end(), referenceNumbers(group.group())));
        }
        Matcher bare = REFERENCE_NUMBER.matcher(answer);
        while (bare.find()) {
            int position = bare.start();
            boolean covered = spans.stream().anyMatch(s -> position >= s.start() && position < s.end());
            if (!covered) {
                spans.add(new Span(position, bare.end(), List.of(Integer.parseInt(bare.group(1)))));
            }
        }
        spans.sort(Comparator.comparingInt(Span::start));
        return spans;
    }

    private static List<Integer> referenceNumbers(String text) {
        List<Integer> numbers = new ArrayList<>();
        Matcher matcher = REFERENCE_NUMBER.matcher(text);
        while (matcher.find()) {
            numbers.add(Integer.parseInt(matcher.group(1)));
        }
        return numbers;
    }

    /**
     * 取引用位置之前、上一个句子边界（或上一个引用）之后的文本作为被引用的句子
     */
    private static String claimBefore(String answer, int citationStart, int previousEnd, int maxClaimChars) {
        int cursor = citationStart - 1;
        while (cursor >= previousEnd && Character.isWhitespace(answer.charAt(cursor))) {
            cursor--;
        }
        // 引用写在句号之后时，跳过这个句号再找上一句的边界
        if (cursor >= previousEnd && SENTENCE_TERMINATORS.indexOf(answer.charAt(cursor)) >= 0) {
            cursor--;
        }
        int start = previousEnd;
        for (int i = cursor; i >= previousEnd; i--) {
            char c = answer.charAt(i);
            if (c == '\n' || SENTENCE_TERMINATORS.indexOf(c) >= 0) {
                start = i + 1;
                break;
            }
        }
        String claim = answer.substring(start, citationStart);
        claim = CITATION_GROUP.matcher(claim).replaceAll("");
        claim = BARE_CITATION.matcher(claim).replaceAll("");
        claim = claim.replace("**", "").replace("`", "").trim();
        String previous;
        do {
            previous = claim;
            claim = LEADING_NOISE.matcher(claim).replaceFirst("").trim();
        } while (!claim.equals(previous));
        if (claim.length() > maxClaimChars) {
            claim = claim.substring(claim.length() - maxClaimChars);
        }
        return claim;
    }

    public record CitedClaim(String claim, List<Integer> referenceNumbers) {
    }

    private record Span(int start, int end, List<Integer> references) {
    }
}
