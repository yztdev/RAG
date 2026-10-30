package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 问答请求的整体时间预算
 */
@Data
@Component
@ConfigurationProperties(prefix = "chat.deadline")
public class ChatDeadlineProperties {

    /** 从收到消息到回答结束的总时间预算 */
    private long totalSeconds = 120L;
    /** 给最终回答预留的时间：工具执行只能用到截止时间减去这段，剩余时间不足时不再进入新的工具轮 */
    private long finalAnswerReserveSeconds = 30L;
    /** 再进行一轮"模型决策 + 工具执行"至少需要的时间，加上最终回答预留后仍不够就提前收尾 */
    private long minToolRoundSeconds = 15L;
}
