package com.dataocean.module.fieldtag.service;

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
