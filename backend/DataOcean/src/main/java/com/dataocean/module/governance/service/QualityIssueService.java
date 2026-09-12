package com.dataocean.module.governance.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.governance.entity.vo.IssueBatchHandleResultVO;
import com.dataocean.module.governance.entity.vo.QualityIssueVO;

/**
 * 元数据质量问题服务。
 */
public interface QualityIssueService {

    /**
     * 分页查询质量问题。
     *
     * @param snapshotId 快照 ID
     * @param dimension  可选质量维度
     * @param severity   可选严重级别
     * @param status     可选处理状态
     * @param tableName  可选表名
     * @param assigneeId 可选责任人用户 ID（null 表示不按责任人过滤）
     * @param page       页码
     * @param size       每页条数
     * @return 质量问题分页结果
     */
    Page<QualityIssueVO> listIssues(Long snapshotId, String dimension, String severity,
                                     String status, String tableName, Long assigneeId, int page, int size);

    /**
     * 处理单个质量问题状态。
     *
     * @param issueId         质量问题 ID
     * @param targetStatus    目标状态
     * @param resolutionNote  处理说明
     * @param operatorId      操作人 ID
     */
    void handleIssue(Long issueId, String targetStatus, String resolutionNote, Long operatorId);

    /**
     * 批量处理质量问题状态。
     * <p>
     * 状态不允许流转的条目会被跳过，调用方需要知道跳过了哪些、为什么，
     * 因此返回结构包含跳过明细而非仅成功计数。本方法不抛异常：部分失败不是异常，
     * 是正常结果，应由调用方如实呈现。
     * </p>
     *
     * @param issueIds      质量问题 ID 列表
     * @param targetStatus  目标状态
     * @param operatorId    操作人 ID
     * @return 处理结果，含成功数、跳过数与跳过明细
     */
    IssueBatchHandleResultVO batchHandle(java.util.List<Long> issueIds, String targetStatus, Long operatorId);

    /**
     * 分派质量问题负责人。
     *
     * @param issueId     质量问题 ID
     * @param assigneeId  负责人用户 ID
     */
    void assignIssue(Long issueId, Long assigneeId);
}
