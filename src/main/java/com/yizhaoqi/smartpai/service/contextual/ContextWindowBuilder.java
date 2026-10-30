package com.yizhaoqi.smartpai.service.contextual;

import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.service.chunk.ChunkTextMerger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 拼给 LLM 的有界文档窗口：文档标题 + 本批分块前后的相邻分块 + 用标记包起来的本批分块。
 * 文档可能比模型上下文长得多，所以只给分块所在的局部，总长度不超过 maxWindowChars：
 * 先保证本批分块完整（放不下时平均截断），剩余预算前后各一半，前文保留结尾、后文保留开头。
 */
public final class ContextWindowBuilder {

    static final String TARGET_START = "【片段 #%d%s】";
    static final String TARGET_END = "【片段 #%d 结束】";
    private static final int MIN_TARGET_CHARS = 50;

    private ContextWindowBuilder() {
    }

    /**
     * @param title        文档标题（文件名）
     * @param windowChunks 按 chunkId 升序的窗口分块，包含本批要生成说明的分块
     * @param targetIds    本批要生成说明的 chunkId
     */
    public static String build(String title, List<DocumentVector> windowChunks, Set<Integer> targetIds, int maxWindowChars) {
        String header = "文档标题：" + (title == null || title.isBlank() ? "未命名文档" : title.trim())
                + "\n\n文档节选（【片段 #编号】和【片段 #编号 结束】之间是需要写上下文说明的分块）：\n";
        List<Segment> segments = segment(windowChunks, targetIds);
        List<Segment> before = new ArrayList<>();
        List<Segment> targets = new ArrayList<>();
        List<Segment> after = new ArrayList<>();
        for (Segment segment : segments) {
            if (segment.target()) {
                targets.add(segment);
            } else if (targets.isEmpty()) {
                before.add(segment);
            } else {
                after.add(segment);
            }
        }

        int budget = Math.max(maxWindowChars - header.length(), 0);
        List<String> targetBlocks = renderTargets(targets, budget);
        int used = targetBlocks.stream().mapToInt(block -> block.length() + 1).sum();
        // 预留前后文的换行和省略号
        int remaining = Math.max(budget - used - 4, 0);

        String beforeText = joinPlain(before);
        String afterText = joinPlain(after);
        int beforeBudget = remaining / 2;
        int afterBudget = remaining - beforeBudget;
        if (beforeText.length() < beforeBudget) {
            afterBudget += beforeBudget - beforeText.length();
        } else if (afterText.length() < afterBudget) {
            beforeBudget += afterBudget - afterText.length();
        }

        StringBuilder window = new StringBuilder(header);
        String beforePart = tail(beforeText, beforeBudget);
        if (!beforePart.isEmpty()) {
            window.append(beforePart).append('\n');
        }
        targetBlocks.forEach(block -> window.append(block).append('\n'));
        String afterPart = head(afterText, afterBudget);
        if (!afterPart.isEmpty()) {
            window.append(afterPart).append('\n');
        }
        return window.toString();
    }

    /**
     * 相邻分块开头带着上一块结尾的重叠句，非目标分块去掉重叠后再拼；目标分块保留原文，便于模型对照
     */
    private static List<Segment> segment(List<DocumentVector> windowChunks, Set<Integer> targetIds) {
        List<Segment> segments = new ArrayList<>();
        String previous = null;
        for (DocumentVector chunk : windowChunks) {
            String text = chunk.getTextContent() == null ? "" : chunk.getTextContent().trim();
            if (text.isEmpty()) {
                continue;
            }
            boolean target = targetIds.contains(chunk.getChunkId());
            String rendered = target || previous == null ? text : ChunkTextMerger.stripLeadingOverlap(previous, text);
            segments.add(new Segment(chunk.getChunkId(), chunk.getPageNumber(), rendered, target));
            previous = text;
        }
        return segments;
    }

    private static List<String> renderTargets(List<Segment> targets, int budget) {
        List<String> blocks = new ArrayList<>(targets.size());
        int labels = targets.stream().mapToInt(t -> startLabel(t).length() + endLabel(t).length() + 3).sum();
        int textTotal = targets.stream().mapToInt(t -> t.text().length()).sum();
        int perTarget = targets.isEmpty() ? 0 : Math.max((budget - labels) / targets.size(), MIN_TARGET_CHARS);
        boolean truncate = labels + textTotal > budget;
        for (Segment target : targets) {
            String text = truncate ? head(target.text(), perTarget) : target.text();
            blocks.add(startLabel(target) + "\n" + text + "\n" + endLabel(target));
        }
        return blocks;
    }

    private static String startLabel(Segment segment) {
        String page = segment.pageNumber() == null ? "" : "，第 " + segment.pageNumber() + " 页";
        return String.format(TARGET_START, segment.chunkId(), page);
    }

    private static String endLabel(Segment segment) {
        return String.format(TARGET_END, segment.chunkId());
    }

    private static String joinPlain(List<Segment> segments) {
        return String.join("\n\n", segments.stream().map(Segment::text).filter(t -> !t.isEmpty()).toList());
    }

    private static String head(String text, int maxChars) {
        if (maxChars <= 0) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }

    private static String tail(String text, int maxChars) {
        if (maxChars <= 0) {
            return "";
        }
        return text.length() <= maxChars ? text : "…" + text.substring(text.length() - maxChars);
    }

    private record Segment(Integer chunkId, Integer pageNumber, String text, boolean target) {
    }
}
