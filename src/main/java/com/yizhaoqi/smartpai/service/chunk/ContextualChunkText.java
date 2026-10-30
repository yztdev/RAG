package com.yizhaoqi.smartpai.service.chunk;

/**
 * 上下文检索的索引文本约定：上下文说明在前、分块原文在后，中间空一行。
 * 入库、embedding 重建、rerank 必须用同一个拼法，否则同一分块在不同链路里的向量和打分对不上。
 */
public final class ContextualChunkText {

    private ContextualChunkText() {
    }

    /**
     * @param chunkContext 上下文说明，为空时直接返回原文
     * @param chunkText    分块原文
     */
    public static String compose(String chunkContext, String chunkText) {
        String text = chunkText == null ? "" : chunkText;
        if (chunkContext == null || chunkContext.isBlank()) {
            return text;
        }
        return chunkContext.trim() + "\n\n" + text;
    }
}
