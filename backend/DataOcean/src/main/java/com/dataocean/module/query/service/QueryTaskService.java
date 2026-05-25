package com.dataocean.module.query.service;

import com.dataocean.module.query.entity.vo.QueryTaskVO;

/**
 * 查询任务服务接口
 */
public interface QueryTaskService {

    /**
     * 提交查询任务。
     * <p>
     * 创建任务记录，调用 Python Agent 服务执行查询，返回任务 ID。
     * </p>
     *
     * @param userId       用户 ID
     * @param datasourceId 数据源 ID
     * @param question     用户问题
     * @param conversationId 会话 ID（可选）
     * @return 任务 ID（UUID）
     */
    String submitQuery(Long userId, Long datasourceId, String question, Long conversationId);

    /**
     * 查询任务结果。
     *
     * @param taskId 任务 ID
     * @return 任务结果 VO
     */
    QueryTaskVO getTaskResult(String taskId);

    /**
     * 取消查询任务。
     *
     * @param taskId 任务 ID
     */
    void cancelTask(String taskId);

    /**
     * 更新任务结果（Python 回调或 SSE 完成后调用）。
     *
     * @param taskId 任务 ID
     * @param result 结果 JSON
     */
    void updateTaskResult(String taskId, String result);
}
