package com.dataocean.module.query.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 问数源端调用与平台其他周期调度分开，有界消费队列。
 */
@Configuration
class QueryScheduling {

    /** 本期单消费者串行派发，数据库继续按请求号争抢。 */
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler queryScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        // 查询和已交付结果的解释各占一个有界轮询任务。
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("query-worker-");
        return scheduler;
    }
}
