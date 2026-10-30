package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 父子检索（small-to-big）：检索仍然在小分块上做，命中后把分块连同同一文件里前后相邻的分块（父块）交给模型。
 * 引用、页码、证据片段仍然指向命中的小分块；多个命中落在同一个父块里时父块只发一次。
 */
@Data
@Component
@ConfigurationProperties(prefix = "search.parent-context")
public class ParentContextProperties {

    /** 总开关，关闭时模型只看命中的小分块 */
    private boolean enabled = true;
    /** 命中分块前后各最多扩展几个相邻分块 */
    private int neighbourChunks = 1;
    /** 单个父块最大字符数（含命中分块本身），从命中分块向后、向前交替扩展直到放不下 */
    private int maxCharsPerHit = 1500;
    /** 一次检索结果里所有父块的总字符数上限，超出后剩余命中只给小分块 */
    private int maxTotalChars = 6000;
}
