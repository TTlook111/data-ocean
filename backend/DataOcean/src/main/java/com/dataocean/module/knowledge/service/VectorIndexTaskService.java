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
     * 按目标查询向量化任务（最新在前）。
     * <p>
     * 此前 `VectorIndexTask` 只存在于 `module/knowledge/` 内部，全项目没有任何
     * Controller 引用它，因此文档处于 `INDEXING` 状态时前端只能显示状态、
     * 无法显示进度与失败原因，重复发布也只能靠后端报错拦截。
     * </p>
     *
     * @param targetType 目标类型（如 CHUNK、DOC）
     * @param targetId   目标 ID
     * @return 该目标的任务列表，按 ID 倒序
     */
    List<VectorIndexTask> listTasksByTarget(String targetType, Long targetId);

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
     * 新版本已发布，但旧版本向量清理失败，等待后续调度重试。
     *
     * @param taskId       任务 ID
     * @param errorMessage 清理失败信息
     */
    void markCleanupPending(Long taskId, String errorMessage);

    /**
     * 更新任务状态为失败。
     *
     * @param taskId       任务 ID
     * @param errorMessage 错误信息
     */
    void markFailed(Long taskId, String errorMessage);
}
