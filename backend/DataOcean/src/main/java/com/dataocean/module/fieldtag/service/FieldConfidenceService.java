package com.dataocean.module.fieldtag.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.fieldtag.entity.vo.ConfidenceEventVO;
import com.dataocean.module.fieldtag.entity.vo.ConfidenceVO;

import java.util.List;

/**
 * 字段可信度服务接口
 * <p>
 * 提供可信度的查询、初始化和管理员设置功能。
 * 可信度调整逻辑委托给 ConfidenceCalculator。
 * </p>
 */
public interface FieldConfidenceService {

    /**
     * 分页查询字段可信度列表（仅返回真实存在可信度记录的字段，并补全字段名/表名）
     *
     * @param page          页码（从 1 开始）
     * @param pageSize      每页条数
     * @param level         可选的等级过滤（HIGH/MEDIUM/LOW），为空则不过滤
     * @param datasourceId  可选的数据源过滤，为空则不过滤
     * @return 可信度视图对象分页结果
     */
    Page<ConfidenceVO> pageConfidence(int page, int pageSize, String level, Long datasourceId);

    /**
     * 查询单个字段的可信度
     *
     * @param columnMetaId 字段元数据ID
     * @return 可信度视图对象，不存在返回 null
     */
    ConfidenceVO getConfidence(Long columnMetaId);

    /**
     * 批量查询字段可信度
     *
     * @param columnMetaIds 字段元数据ID列表
     * @return 可信度视图对象列表
     */
    List<ConfidenceVO> batchGetConfidence(List<Long> columnMetaIds);

    /**
     * 管理员手动设置可信度分数
     *
     * @param columnMetaId 字段元数据ID
     * @param score        目标分数（0-100）
     * @param reason       设置原因
     * @return 设置后的可信度视图
     */
    ConfidenceVO adminSetScore(Long columnMetaId, int score, String reason);

    /**
     * 查询字段可信度变更历史
     *
     * @param columnMetaId 字段元数据ID
     * @return 变更事件列表（按时间倒序）
     */
    List<ConfidenceEventVO> getEventHistory(Long columnMetaId);
}
