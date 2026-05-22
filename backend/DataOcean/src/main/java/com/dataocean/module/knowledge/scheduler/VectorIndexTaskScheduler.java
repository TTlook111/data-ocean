package com.dataocean.module.knowledge.scheduler;

import com.dataocean.module.knowledge.entity.VectorIndexTask;
import com.dataocean.module.knowledge.service.VectorIndexTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量化任务定时调度器
 * <p>
 * 每 5 分钟扫描 PENDING 状态的向量化任务，
 * 调用 007 模块的向量化接口处理。
 * </p>
 *
 * @author dataocean
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VectorIndexTaskScheduler {

    private final VectorIndexTaskService vectorIndexTaskService;

    /**
     * 定时扫描并处理待向量化任务
     * <p>
     * 每 5 分钟执行一次，查询所有 PENDING 状态的任务，
     * 逐个标记为 PROCESSING 后调用向量化接口。
     * MVP 阶段暂时只更新状态为 COMPLETED，待 007 模块就绪后接入实际调用。
     * </p>
     */
    @Scheduled(fixedDelay = 300000)
    public void processVectorTasks() {
        // 查询所有待处理的向量化任务
        List<VectorIndexTask> pendingTasks = vectorIndexTaskService.listPendingTasks();
        if (pendingTasks.isEmpty()) {
            return;
        }
        log.info("扫描到待处理向量化任务 count={}", pendingTasks.size());
        for (VectorIndexTask task : pendingTasks) {
            try {
                // 标记为处理中
                vectorIndexTaskService.markProcessing(task.getId());
                // TODO: 调用 007 模块 /internal/rag/vectorize 接口
                // pythonRagClient.vectorize(task.getDatasourceId(), task.getTargetType(), task.getTargetId());
                // MVP 阶段暂时直接标记为完成
                vectorIndexTaskService.markCompleted(task.getId());
                log.info("向量化任务处理完成 taskId={} targetType={} targetId={}",
                        task.getId(), task.getTargetType(), task.getTargetId());
            } catch (Exception e) {
                // 处理失败，记录错误信息
                log.error("向量化任务处理失败 taskId={}", task.getId(), e);
                vectorIndexTaskService.markFailed(task.getId(), e.getMessage());
            }
        }
    }
}
