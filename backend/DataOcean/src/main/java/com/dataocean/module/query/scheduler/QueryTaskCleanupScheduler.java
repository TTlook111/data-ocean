package com.dataocean.module.query.scheduler;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataocean.module.query.entity.QueryTask;
import com.dataocean.module.query.enums.QueryTaskStatus;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 查询任务僵尸清理定时任务。
 * <p>
 * 每 60 秒扫描一次，将超过 3 分钟仍处于 PROCESSING 状态的任务
 * 标记为 TIMEOUT，防止因 Python 服务异常或网络中断导致任务永久停留在处理中。
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "dataocean.query.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class QueryTaskCleanupScheduler {

    private final QueryTaskMapper queryTaskMapper;

    /**
     * 清理僵尸任务。
     * <p>
     * 使用 LambdaUpdateWrapper 同时包含 status='PROCESSING' 条件，
     * 保证原子性：如果任务在扫描到更新之间已被正常完成或取消，
     * 则 WHERE 条件不满足，不会错误覆盖终态。
     * </p>
     */
    @Scheduled(fixedRate = 60000)
    public void cleanupZombieTasks() {
        // 超时阈值：创建时间早于当前时间 3 分钟
        LocalDateTime timeoutThreshold = LocalDateTime.now().minusMinutes(3);

        // 构造原子更新：仅更新 status 仍为 PROCESSING 且创建时间超过阈值的任务
        LambdaUpdateWrapper<QueryTask> wrapper = new LambdaUpdateWrapper<QueryTask>()
                .eq(QueryTask::getStatus, QueryTaskStatus.PROCESSING.name())
                .lt(QueryTask::getCreatedAt, timeoutThreshold)
                .set(QueryTask::getStatus, QueryTaskStatus.TIMEOUT.name())
                .set(QueryTask::getErrorMessage, "查询执行超时（任务清理）")
                .set(QueryTask::getCompletedAt, LocalDateTime.now());

        int updated = queryTaskMapper.update(null, wrapper);
        if (updated > 0) {
            log.info("清理僵尸任务完成，超时任务数={}", updated);
        }
    }
}
