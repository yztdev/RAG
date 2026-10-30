package com.yizhaoqi.smartpai.service.conflict;

import java.util.Set;

/**
 * 规则筛出的疑似冲突分块对（尚未落库）
 */
public record ConflictCandidate(
        ChunkRef newChunk,
        ChunkRef existingChunk,
        double similarity,
        double maskedSimilarity,
        Set<String> newOnlyValues,
        Set<String> existingOnlyValues,
        String llmReason
) {

    public record ChunkRef(String fileMd5, int chunkId, String text) {
    }

    public ConflictCandidate withLlmReason(String reason) {
        return new ConflictCandidate(newChunk, existingChunk, similarity, maskedSimilarity,
                newOnlyValues, existingOnlyValues, reason);
    }

    /**
     * 与方向无关的分块对标识：A 和 B 谁先入库都得到同一个 key，用于去重
     */
    public String pairKey() {
        String a = newChunk.fileMd5() + "#" + newChunk.chunkId();
        String b = existingChunk.fileMd5() + "#" + existingChunk.chunkId();
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }
}
