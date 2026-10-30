package com.yizhaoqi.smartpai.service.chunk;

import java.util.List;

/**
 * 把同一文件里相邻的分块拼回连续文本。
 * 切分时每块开头会带上上一块结尾的几句作为重叠（overlapText + 空行 + 本块正文），直接拼接会让这几句重复出现，
 * 这里尽力去掉重叠部分；识别不出重叠时原样拼接，宁可重复几句也不丢内容。
 */
public final class ChunkTextMerger {

    static final String SEPARATOR = "\n\n";
    private static final int MIN_LITERAL_OVERLAP = 12;
    private static final int MAX_LITERAL_PROBE = 600;

    private ChunkTextMerger() {
    }

    public static String merge(List<String> orderedChunks) {
        if (orderedChunks == null || orderedChunks.isEmpty()) {
            return "";
        }
        StringBuilder merged = new StringBuilder();
        String previous = null;
        for (String chunk : orderedChunks) {
            if (chunk == null || chunk.isBlank()) {
                continue;
            }
            String text = chunk.trim();
            String addition = previous == null ? text : stripLeadingOverlap(previous, text);
            if (!addition.isEmpty()) {
                if (!merged.isEmpty()) {
                    merged.append(SEPARATOR);
                }
                merged.append(addition);
            }
            previous = text;
        }
        return merged.toString();
    }

    /**
     * 去掉 next 开头与 previous 结尾重复的部分
     */
    public static String stripLeadingOverlap(String previous, String next) {
        int separator = next.indexOf(SEPARATOR);
        if (separator > 0) {
            String head = next.substring(0, separator);
            String compactHead = compact(head);
            if (!compactHead.isEmpty() && compact(previous).endsWith(compactHead)) {
                return next.substring(separator + SEPARATOR.length()).trim();
            }
        }
        int maxProbe = Math.min(Math.min(previous.length(), next.length() - 1), MAX_LITERAL_PROBE);
        for (int length = maxProbe; length >= MIN_LITERAL_OVERLAP; length--) {
            if (previous.endsWith(next.substring(0, length))) {
                return next.substring(length).trim();
            }
        }
        return next;
    }

    private static String compact(String text) {
        return text.replaceAll("\\s+", "");
    }
}
