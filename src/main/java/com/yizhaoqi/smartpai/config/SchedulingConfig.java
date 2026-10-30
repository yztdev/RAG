package com.yizhaoqi.smartpai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用定时任务：卡住任务恢复、废弃上传清理、outbox 补发。
 * 多实例部署时每个实例都会执行，靠条件更新保证同一条记录只被一个实例处理
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
