package com.dataocean.module.permission.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.permission.entity.AccessApprovalRequest;

/**
 * 数据访问审批服务接口
 *
 * @author dataocean
 */
public interface AccessApprovalService {

    /**
     * 提交数据访问审批请求
     *
     * @param request 审批请求（requesterId 从上下文获取）
     * @return 创建的请求 ID
     */
    Long submitRequest(AccessApprovalRequest request);

    /**
     * 审批请求
     *
     * @param requestId  请求 ID
     * @param approverId 审批人 ID
     * @param approved   是否通过
     * @param reason     审批理由（拒绝时必填）
     */
    void reviewRequest(Long requestId, Long approverId, boolean approved, String reason);

    /**
     * 查询审批请求列表
     *
     * @param datasourceId 数据源 ID（可选）
     * @param status       状态（可选）
     * @param page         页码
     * @param size         每页大小
     * @return 分页结果
     */
    Page<AccessApprovalRequest> listRequests(Long datasourceId, String status, int page, int size);

    /**
     * 过期已到期的临时策略
     * <p>
     * 由定时任务调用，将 expires_at < NOW() 的 APPROVED 请求标记为 EXPIRED，
     * 同时删除对应的临时 ALLOW 策略。
     * </p>
     *
     * @return 过期处理数量
     */
    int expireOverdueRequests();
}
