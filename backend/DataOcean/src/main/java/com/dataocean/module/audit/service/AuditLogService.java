package com.dataocean.module.audit.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.audit.entity.QueryAuditLog;
import com.dataocean.module.audit.entity.dto.AuditLogQueryDTO;
import com.dataocean.module.audit.entity.vo.AuditLogVO;
import com.dataocean.module.audit.entity.vo.AuditStatsVO;

/**
 * 审计日志服务接口
 * <p>
 * 提供查询审计日志的异步写入、查询、统计和导出功能。
 * </p>
 */
public interface AuditLogService {

    /**
     * 异步记录审计日志
     * <p>
     * 在查询完成后调用，自动标记慢查询。使用 @Async 确保不阻塞主流程。
     * </p>
     *
     * @param queryTaskId 查询任务ID
     */
    void recordAudit(Long queryTaskId);

    /**
     * 更新用户反馈
     *
     * @param queryTaskId  查询任务ID
     * @param feedbackType 反馈类型：LIKE/DISLIKE
     */
    void updateFeedback(Long queryTaskId, String feedbackType);

    /**
     * 分页查询审计日志
     *
     * @param query 查询条件
     * @return 分页结果
     */
    Page<AuditLogVO> listAuditLogs(AuditLogQueryDTO query);

    /**
     * 获取审计日志详情
     *
     * @param id 审计日志ID
     * @return 审计日志详情
     */
    AuditLogVO getAuditLogDetail(Long id);

    /**
     * 查询慢查询列表
     *
     * @param page     页码
     * @param pageSize 每页大小
     * @return 慢查询分页结果
     */
    Page<AuditLogVO> listSlowQueries(int page, int pageSize);

    /**
     * 查询审计统计数据
     *
     * @param datasourceId 数据源ID（可选）
     * @param days         统计天数
     * @return 统计结果
     */
    AuditStatsVO getStats(Long datasourceId, int days);

    /**
     * 将查询提升为模板
     *
     * @param auditLogId 审计日志ID
     */
    void promoteToTemplate(Long auditLogId);
}
