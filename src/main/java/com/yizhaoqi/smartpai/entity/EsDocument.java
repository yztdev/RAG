package com.yizhaoqi.smartpai.entity;


import lombok.Data;

/**
 * Elasticsearch存储的文档实体类
 * 包含文档内容和权限信息
 */
@Data
public class EsDocument {

    private String id;             // 文档唯一标识
    private String fileMd5;        // 文件指纹
    private Integer chunkId;       // 文本分块序号
    private String fileName;       // 文件名，单独参与关键词打分（标题性强的文档在对应提问下排得更前）
    private String textContent;    // 分块原文（展示、引用、证据片段只用这个字段）
    private String chunkContext;   // 上下文检索生成的分块说明，只参与 BM25 与 embedding，可为空
    private Integer pageNumber;    // PDF 页码
    private String anchorText;     // 页内定位锚点
    private float[] vector;        // 向量数据（768维）
    private String modelVersion;   // 向量生成模型版本
    private String userId;         // 上传用户ID
    private String orgTag;         // 组织标签
    private boolean isPublic;      // 是否公开

    /**
     * 默认构造函数，用于Jackson反序列化
     */
    public EsDocument() {
    }

    /**
     * 完整构造函数，包含权限字段
     */
    public EsDocument(String id, String fileMd5, int chunkId, String content,
                     Integer pageNumber, String anchorText,
                     float[] vector, String modelVersion, 
                     String userId, String orgTag, boolean isPublic) {
        this(id, fileMd5, chunkId, content, null, pageNumber, anchorText, vector, modelVersion, userId, orgTag, isPublic);
    }

    /**
     * 带上下文说明的构造函数：textContent 保存原文，chunkContext 单独存放
     */
    public EsDocument(String id, String fileMd5, int chunkId, String content, String chunkContext,
                     Integer pageNumber, String anchorText,
                     float[] vector, String modelVersion,
                     String userId, String orgTag, boolean isPublic) {
        this.id = id;
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = content;
        this.chunkContext = chunkContext;
        this.pageNumber = pageNumber;
        this.anchorText = anchorText;
        this.vector = vector;
        this.modelVersion = modelVersion;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
    }
    

}
