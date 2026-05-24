package com.dataocean.module.knowledge.service;

import com.dataocean.module.knowledge.entity.VectorIndexTask;

import java.util.List;

/**
 * 向量化任务管理业务接口。
 * <p>
 * 管理知识切片向量化到 Milvus 的异步任务生命周期。
 * Java 创建任务后调用 Python 服务执行向量化，Python 回调更新任务状态。
 * </p>
 *
 * @author DataOcean
 */
public interface VectorIndexTaskService {

    /**
     * 创建向量化任务。
     * <p>
     * 初始状态为 PENDING，等待调度执行。
     * </p>
     *
     * @param datasourceId 数据源 ID
     * @param targetType   目标类型（如 CHUNK、DOC）
     * @param targetId     目标 ID
     * @return 任务 ID
     */
    Long createTask(Long datasourceId, String targetType, Long targetId);

    Long createTask(Long datasourceId,
                    String targetType,
                    Long targetId,
                    Long metadataSnapshotId,
                    Integer knowledgeVersionNo,
                    Integer previousVersionNo);

    /**
     * 查询待处理的任务列表。
     *
     * @return PENDING 状态的任务列表
     */
    List<VectorIndexTask> listPendingTasks();

    /**
     * 更新任务状态为处理中。
     *
     * @param taskId 任务 ID
     */
    void markProcessing(Long taskId);

    /**
     * 更新任务状态为已完成。
     *
     * @param taskId 任务 ID
     */
    void markCompleted(Long taskId);

    /**
     * 更新任务状态为失败。
     *
     * @param taskId       任务 ID
     * @param errorMessage 错误信息
     */
    void markFailed(Long taskId, String errorMessage);
}
