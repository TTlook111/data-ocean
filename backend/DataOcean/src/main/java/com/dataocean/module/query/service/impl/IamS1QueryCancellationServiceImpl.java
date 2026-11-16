package com.dataocean.module.query.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.query.client.IamS1PythonClient;
import com.dataocean.module.query.entity.po.QueryTask;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.dataocean.module.query.service.IamS1QueryCancellationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/** 用任务行中的取消意图保证可恢复交付；网络调用不占用本地数据库事务。 */
@Service
@Slf4j
public class IamS1QueryCancellationServiceImpl implements IamS1QueryCancellationService {
    private static final int RETRY_DELAY_SECONDS = 5;
    private static final int RETRY_BATCH_SIZE = 20;

    private final QueryTaskMapper taskMapper;
    private final IamS1PythonClient pythonClient;
    private final Executor cancellationExecutor;
    private final TransactionTemplate deliveryTransaction;
    private final Set<String> scheduledTasks = ConcurrentHashMap.newKeySet();

    public IamS1QueryCancellationServiceImpl(QueryTaskMapper taskMapper, IamS1PythonClient pythonClient,
                                           PlatformTransactionManager transactionManager,
                                           @Qualifier("queryCancellationExecutor") Executor cancellationExecutor) {
        // afterCommit 阶段原事务资源可能仍绑定，投递标记必须使用可独立提交的新事务。
        this.taskMapper = taskMapper;
        this.pythonClient = pythonClient;
        this.cancellationExecutor = cancellationExecutor;
        this.deliveryTransaction = new TransactionTemplate(transactionManager);
        this.deliveryTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void dispatchAfterCommit(String taskId) {
        // 回滚不会触发 afterCommit；提交后丢失本回调时，PENDING 仍由定时补投接管。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    schedule(taskId);
                }
            });
        } else {
            schedule(taskId);
        }
    }

    @Override
    public void retryPending() {
        // 时间只控制下次尝试，不淘汰未收到回执的意图；排序使失败任务不会饿死后续任务。
        LocalDateTime now = LocalDateTime.now();
        var pending = taskMapper.selectList(new LambdaQueryWrapper<QueryTask>()
                .eq(QueryTask::getIamProtocolVersion, IamS1Constants.PROTOCOL_VERSION)
                .in(QueryTask::getStatus, "CANCELLED", "TIMEOUT")
                .eq(QueryTask::getCancellationDeliveryStatus, "PENDING")
                .and(query -> query.isNull(QueryTask::getCancellationNextAttemptAt)
                        .or().le(QueryTask::getCancellationNextAttemptAt, now))
                .orderByAsc(QueryTask::getCancellationNextAttemptAt, QueryTask::getId)
                .last("LIMIT " + RETRY_BATCH_SIZE));
        for (QueryTask task : pending) schedule(task.getTaskId());
    }

    private void schedule(String taskId) {
        // 单机内同一任务只排队一次；该集合不承担持久可靠性，重启后仍以数据库为准。
        if (!scheduledTasks.add(taskId)) return;
        try {
            cancellationExecutor.execute(() -> {
                try {
                    deliver(taskId);
                } catch (Exception ex) {
                    // 远端或本地回执落库失败都保留 PENDING，日志不输出响应正文或凭据。
                    log.warn("S1 取消投递未确认，保留补投 taskId={} reason={}", taskId,
                            ex.getClass().getSimpleName());
                } finally {
                    scheduledTasks.remove(taskId);
                }
            });
        } catch (RuntimeException ex) {
            // 有界线程池拒绝时不回退到请求线程，也不清除数据库里的待投递意图。
            scheduledTasks.remove(taskId);
            log.warn("S1 取消投递排队失败，保留补投 taskId={} reason={}", taskId,
                    ex.getClass().getSimpleName());
        }
    }

    private void deliver(String taskId) {
        // 仅领取已经取消或超时的 S1 待投递行；完成任务和已接收意图均不会再次发送。
        LocalDateTime now = LocalDateTime.now();
        boolean claimed = Boolean.TRUE.equals(deliveryTransaction.execute(status -> taskMapper.update(null,
                pendingTask(taskId).and(query -> query.isNull(QueryTask::getCancellationNextAttemptAt)
                                .or().le(QueryTask::getCancellationNextAttemptAt, now))
                        .set(QueryTask::getCancellationNextAttemptAt, now.plusSeconds(RETRY_DELAY_SECONDS))) == 1));
        if (!claimed) return;

        // taskId 是取消操作的稳定身份；超时后的重复投递只重置同一取消标记，不重跑任务。
        pythonClient.cancelTask(taskId);

        // ACK 只证明 Python 接收了取消意图；不写执行已停止，也不改任务终态或轮次。
        deliveryTransaction.executeWithoutResult(status -> taskMapper.update(null, pendingTask(taskId)
                .set(QueryTask::getCancellationDeliveryStatus, "ACCEPTED")
                .set(QueryTask::getCancellationNextAttemptAt, null)));
    }

    private LambdaUpdateWrapper<QueryTask> pendingTask(String taskId) {
        // 领取与回执更新共享保护条件，迟到响应不能触碰已成功终态或其他协议的任务。
        return new LambdaUpdateWrapper<QueryTask>()
                .eq(QueryTask::getTaskId, taskId)
                .eq(QueryTask::getIamProtocolVersion, IamS1Constants.PROTOCOL_VERSION)
                .in(QueryTask::getStatus, "CANCELLED", "TIMEOUT")
                .eq(QueryTask::getCancellationDeliveryStatus, "PENDING");
    }
}
