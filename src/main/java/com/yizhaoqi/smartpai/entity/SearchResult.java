package com.yizhaoqi.smartpai.entity;

import lombok.Data;

@Data
public class SearchResult {
    private String fileMd5;    // 文件指纹
    private Integer chunkId;   // 文本分块序号
    private String textContent; // 文本内容
    private Double score;      // 统一换算到 0 到 1 的得分，不同召回方式之间可比
    private Double rawScore;   // 换算前的原始分（rerank 分 / RRF 分 / BM25 分），配合 retrievalMode 排查
    private String fileName;   // 原始文件名
    private String userId;     // 上传用户ID
    private String orgTag;     // 组织标签
    private Boolean isPublic;  // 是否公开
    private Integer pageNumber; // PDF 页码
    private String anchorText; // 页内定位锚点
    private String retrievalMode; // 召回方式
    private String matchedChunkText; // 命中的 chunk 原文
    private String parentContext; // 父子检索：命中分块连同相邻分块的父块文本，只交给模型，不用于引用和证据片段
    private String parentKey;     // 父块标识 fileMd5#起始chunkId-结束chunkId，多个命中共用一个父块时相同

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score) {
        this(fileMd5, chunkId, textContent, score, null, null, false, null, null, null, null, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String fileName) {
        this(fileMd5, chunkId, textContent, score, null, null, false, fileName, null, null, null, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag, boolean isPublic) {
        this(fileMd5, chunkId, textContent, score, userId, orgTag, isPublic, null, null, null, null, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag, boolean isPublic, String fileName) {
        this(fileMd5, chunkId, textContent, score, userId, orgTag, isPublic, fileName, null, null, null, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag,
                        boolean isPublic, String fileName, Integer pageNumber, String anchorText) {
        this(fileMd5, chunkId, textContent, score, userId, orgTag, isPublic, fileName, pageNumber, anchorText, null, textContent);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag,
                        boolean isPublic, String fileName, Integer pageNumber, String anchorText,
                        String retrievalMode, String matchedChunkText) {
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = textContent;
        this.score = score;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.fileName = fileName;
        this.pageNumber = pageNumber;
        this.anchorText = anchorText;
        this.retrievalMode = retrievalMode;
        this.matchedChunkText = matchedChunkText != null ? matchedChunkText : textContent;
    }

    /**
     * 返回附带父块的新对象，原对象不变；引用相关字段（chunkId、页码、锚点、命中原文）保持指向命中的小分块
     */
    public SearchResult withParentContext(String parentContext, String parentKey) {
        SearchResult copy = new SearchResult(fileMd5, chunkId, textContent, score, userId, orgTag,
                Boolean.TRUE.equals(isPublic), fileName, pageNumber, anchorText, retrievalMode, matchedChunkText);
        copy.setRawScore(rawScore);
        copy.setParentContext(parentContext);
        copy.setParentKey(parentKey);
        return copy;
    }
}
