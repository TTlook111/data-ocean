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
     * 按**负责源范围**分页查询审计日志。
     *
     * <p>`visibleDatasourceIds` 必须是调用者在 `audit:view` 上负责的数据源：空集合直接返回空页，
     * 不退化成全局查询；范围下推到 SQL（先分页再在内存过滤会让总数与分页边界出错）。</p>
     *
     * <p>调用方显式筛选的 `datasourceId` 不在负责范围内时抛出 403——返回空页会把「无权」
     * 伪装成「这个数据源没有审计记录」。</p>
     */
    Page<AuditLogVO> listAuditLogsInDatasources(AuditLogQueryDTO query, java.util.Collection<Long> visibleDatasourceIds);

    /**
     * 查询慢查询列表
     *
     * @param page     页码
     * @param pageSize 每页大小
     * @return 慢查询分页结果
     */
    Page<AuditLogVO> listSlowQueries(int page, int pageSize);

    /** 按负责源范围查询慢查询；空范围返回空页。 */
    Page<AuditLogVO> listSlowQueriesInDatasources(int page, int pageSize, java.util.Collection<Long> visibleDatasourceIds);

    /**
     * 查询审计统计数据
     *
     * @param datasourceId 数据源ID（可选）
     * @param days         统计天数
     * @return 统计结果
     */
    AuditStatsVO getStats(Long datasourceId, int days);

    /** 按负责源范围统计；空范围返回全零统计而不是全局统计。 */
    AuditStatsVO getStatsInDatasources(Long datasourceId, int days, java.util.Collection<Long> visibleDatasourceIds);

}
