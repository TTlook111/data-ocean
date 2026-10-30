package com.dataocean.module.audit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.query.event.QueryTraceCommittedEvent;
import com.dataocean.module.query.entity.po.QueryTask;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 提交后尽快投影，轮询恢复提交后通知丢失及进程中断，不重跑 SQL。 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "dataocean.query.trace-projection.enabled", havingValue = "true", matchIfMissing = true)
public class QueryTraceProjectionWorker {
    private final QueryTaskMapper tasks;
    private final QueryTraceProjectionService projection;
    private long recoveryCursor;
    private long recoveryThrough;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(QueryTraceCommittedEvent event) { attempt(event.taskId()); }

    @Scheduled(fixedDelayString = "${dataocean.query.trace-projection.recovery-delay-ms:30000}")
    public void recover() {
        if (recoveryThrough == 0) {
            var last = tasks.selectOne(new LambdaQueryWrapper<QueryTask>().eq(QueryTask::getTraceProjectionStatus, "PENDING")
                    .eq(QueryTask::getStatus, "COMPLETED").orderByDesc(QueryTask::getId).last("LIMIT 1"));
            if (last == null) return;
            recoveryThrough = last.getId();
        }
        var pending = tasks.selectList(new LambdaQueryWrapper<QueryTask>()
                .eq(QueryTask::getTraceProjectionStatus, "PENDING").eq(QueryTask::getStatus, "COMPLETED")
                .gt(QueryTask::getId, recoveryCursor).le(QueryTask::getId, recoveryThrough).orderByAsc(QueryTask::getId).last("LIMIT 50"));
        if (pending.isEmpty()) { recoveryCursor = 0; recoveryThrough = 0; return; }
        for (QueryTask task : pending) { recoveryCursor = task.getId(); attempt(task.getTaskId()); }
        if (recoveryCursor >= recoveryThrough) { recoveryCursor = 0; recoveryThrough = 0; }
    }

    private void attempt(String taskId) {
        try { projection.project(taskId); }
        catch (RuntimeException exception) {
            log.warn("查询追溯未完成，将重试 taskId={} exceptionType={}", taskId, exception.getClass().getSimpleName());
        }
    }
}
