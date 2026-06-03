package com.dataocean.module.query.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.query.entity.query.QueryHistoryQuery;
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
     * @param userId 当前用户 ID（用于权限校验）
     * @return 任务结果 VO
     */
    QueryTaskVO getTaskResult(String taskId, Long userId);

    Page<QueryTaskVO> listHistory(Long userId, QueryHistoryQuery query);

    /**
     * 取消查询任务。
     *
     * @param taskId 任务 ID
     * @param userId 当前用户 ID（用于权限校验）
     */
    void cancelTask(String taskId, Long userId);

    /**
     * 更新任务结果（Python 回调或 SSE 完成后调用）。
     * <p>
     * 如果任务已被取消则跳过更新。
     * </p>
     *
     * @param taskId 任务 ID
     * @param result 结果 JSON
     * @return true 表示实际更新了结果，false 表示任务已取消被跳过
     */
    boolean updateTaskResult(String taskId, String result);

    /**
     * 更新任务实时进度（消费 Python SSE progress 事件时调用）。
     * <p>
     * 仅在任务仍处于 PROCESSING 状态时更新，已取消/已完成的任务跳过，避免覆盖终态。
     * </p>
     *
     * @param taskId  任务 ID
     * @param node    当前执行节点
     * @param message 进度提示文案
     */
    void updateTaskProgress(String taskId, String node, String message);

    /**
     * 根据任务 UUID 获取数据库主键 ID。
     *
     * @param taskId 任务 UUID
     * @param userId 当前用户 ID（权限校验）
     * @return 数据库主键 ID
     */
    Long getTaskDbId(String taskId, Long userId);
}
