package com.dataocean.module.query.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataocean.module.query.entity.QueryTask;
import com.dataocean.module.query.enums.QueryTaskStatus;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.dataocean.module.query.service.ConversationService;
import com.dataocean.module.query.service.ConversationContextSummaryService;
import com.dataocean.module.query.client.IamS1PythonClient;
import com.dataocean.module.permission.s1.IamS1Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 查询任务僵尸清理定时任务。
 * <p>
 * 每 60 秒扫描一次，将超过 2 分钟仍处于 PROCESSING 状态的任务
 * 标记为 TIMEOUT，防止因 Python 服务异常或网络中断导致任务永久停留在处理中。
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "dataocean.query.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class QueryTaskCleanupScheduler {

    private final QueryTaskMapper queryTaskMapper;
    private final ConversationService conversationService;
    private final ConversationContextSummaryService conversationContextSummaryService;
    private final IamS1PythonClient pythonClient;

    /**
     * 清理僵尸任务。
     * <p>
     * 使用 LambdaUpdateWrapper 同时包含 status='PROCESSING' 条件，
     * 保证原子性：如果任务在扫描到更新之间已被正常完成或取消，
     * 则 WHERE 条件不满足，不会错误覆盖终态。
     * </p>
     */
    @Scheduled(fixedRate = 60000)
    @Transactional
    public void cleanupZombieTasks() {
        // G0 freezes the total query deadline at 90 seconds; allow a bounded
        // handoff margin, then stop both Java and Python sides.
        LocalDateTime timeoutThreshold = LocalDateTime.now().minusMinutes(2);

        // 构造原子更新：仅更新 status 仍为 PROCESSING 且创建时间超过阈值的任务
        List<QueryTask> stale = queryTaskMapper.selectList(new LambdaQueryWrapper<QueryTask>()
                .eq(QueryTask::getStatus, QueryTaskStatus.PROCESSING.name())
                .lt(QueryTask::getCreatedAt, timeoutThreshold));
        int updated = 0;
        for (QueryTask task : stale) {
            int changed = queryTaskMapper.update(null, new LambdaUpdateWrapper<QueryTask>()
                    .eq(QueryTask::getId, task.getId())
                    .eq(QueryTask::getStatus, QueryTaskStatus.PROCESSING.name())
                    .set(QueryTask::getStatus, QueryTaskStatus.TIMEOUT.name())
                    .set(QueryTask::getErrorMessage, "查询执行超时（任务清理）")
                    .set(QueryTask::getIamFinalProtectionStatus, "TIMEOUT")
                    .set(QueryTask::getCompletedAt, LocalDateTime.now()));
            if (changed > 0) {
                updated += changed;
                if (task.getConversationId() != null
                        && IamS1Constants.PROTOCOL_VERSION.equals(task.getIamProtocolVersion())) {
                    pythonClient.cancelTask(task.getTaskId());
                    conversationService.saveAssistantMessage(task.getConversationId(), "查询执行超时，请重新提问",
                            task.getTaskId(), "{\"taskId\":\"" + task.getTaskId() + "\",\"status\":\"TIMEOUT\"}");
                    conversationService.releaseTurn(task.getConversationId(), task.getTaskId());
                    conversationContextSummaryService.refreshAsync(
                            task.getConversationId(), task.getUserId(), task.getDatasourceId());
                }
            }
        }
        if (updated > 0) {
            log.info("清理僵尸任务完成，超时任务数={}", updated);
        }
    }

    /**
     * 清理历史已完成任务。
     * <p>
     * 每天凌晨 3 点执行一次，删除 30 天前已完成/已取消/已超时的任务，
     * 释放数据库存储空间。
     * </p>
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupOldTasks() {
        // 保留阈值：30 天前
        LocalDateTime retentionThreshold = LocalDateTime.now().minusDays(30);

        // 查询需要清理的任务 ID
        LambdaQueryWrapper<QueryTask> queryWrapper = new LambdaQueryWrapper<QueryTask>()
                .in(QueryTask::getStatus,
                        QueryTaskStatus.COMPLETED.name(),
                        QueryTaskStatus.CANCELLED.name(),
                        QueryTaskStatus.TIMEOUT.name())
                .lt(QueryTask::getCreatedAt, retentionThreshold)
                .and(wrapper -> wrapper.isNull(QueryTask::getIamProtocolVersion)
                        .or().ne(QueryTask::getIamProtocolVersion, IamS1Constants.PROTOCOL_VERSION))
                .select(QueryTask::getId);

        List<Long> idsToDelete = queryTaskMapper.selectList(queryWrapper).stream()
                .map(QueryTask::getId)
                .toList();

        if (idsToDelete.isEmpty()) {
            return;
        }

        // 批量删除
        queryTaskMapper.deleteBatchIds(idsToDelete);
        log.info("清理历史任务完成，删除任务数={}", idsToDelete.size());
    }
}
