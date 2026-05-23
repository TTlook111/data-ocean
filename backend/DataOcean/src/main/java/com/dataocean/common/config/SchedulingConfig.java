package com.dataocean.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 定时任务线程池配置。
 * <p>
 * 为元数据同步等后台任务提供统一的 {@link TaskScheduler}，并在应用关闭时等待任务收尾。
 * </p>
 */
@Configuration
public class SchedulingConfig {

    /**
     * 创建定时任务调度器。
     *
     * @return 线程池任务调度器
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("sync-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
