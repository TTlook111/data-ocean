package com.dataocean.module.query.client;

/**
 * Python Agent 服务客户端接口。
 * <p>
 * 调用 Python /internal/query/execute 触发 NL2SQL 工作流，
 * 消费 SSE 事件流并在完成后回调结果。
 * </p>
 */
public interface PythonAgentClient {

    /**
     * 异步触发 Agent 执行查询。
     * <p>
     * 调用 Python SSE 接口，在后台线程消费事件流，
     * 完成后通过 QueryTaskService.updateTaskResult() 回写结果。
     * </p>
     *
     * @param taskId           任务 ID
     * @param datasourceId     数据源 ID
     * @param userId           用户 ID
     * @param question         用户问题
     * @param conversationId   会话 ID
     * @param activeSnapshotId 活跃的元数据快照 ID
     */
    void executeAsync(String taskId, Long datasourceId, Long userId, String question,
                      Long conversationId, Long activeSnapshotId);
}
