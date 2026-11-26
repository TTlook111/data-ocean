package com.dataocean.module.knowledge.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 专用单消费者隔离知识规划的模型等待与普通平台调度。
 */
@Configuration
class KnowledgePlanScheduling {

    /**
     * 每实例同时处理一轮规划，数据库继续约束同一任务的单次领取。
     */
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler knowledgePlanScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("knowledge-plan-");
        return scheduler;
    }
}
