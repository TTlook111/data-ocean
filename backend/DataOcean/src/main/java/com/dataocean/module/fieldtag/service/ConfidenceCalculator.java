package com.dataocean.module.fieldtag.service;

import com.dataocean.module.fieldtag.entity.vo.ConfidenceEventVO;
import com.dataocean.module.fieldtag.entity.vo.ConfidenceVO;

import java.util.List;

/**
 * 可信度计算引擎接口
 * <p>
 * 核心计算逻辑：根据来源初始化分数，根据事件类型调整分数。
 * 每次调整都记录 FieldConfidenceEvent 流水。
 * </p>
 */
public interface ConfidenceCalculator {

    /** 初始分：Schema 注释来源 */
    int INIT_SCORE_SCHEMA = 30;
    /** 初始分：skills.md 定义来源 */
    int INIT_SCORE_SKILLS_MD = 60;
    /** 初始分：人工确认来源 */
    int INIT_SCORE_MANUAL = 90;
    /** 初始分：管理员设定来源 */
    int INIT_SCORE_ADMIN = 100;

    /** 事件加分：查询成功使用 */
    int DELTA_QUERY_SUCCESS = 2;
    /** 事件加分：用户点赞 */
    int DELTA_USER_LIKE = 10;
    /** 事件扣分：用户踩经审核确认 */
    int DELTA_USER_DISLIKE_CONFIRMED = -15;
    /** 事件扣分：群体阈值触发 */
    int DELTA_GROUP_THRESHOLD = -5;

    /**
     * 初始化字段可信度
     * <p>
     * 根据来源设定初始分数，如果已存在则不重复初始化。
     * </p>
     *
     * @param columnMetaId 字段元数据ID
     * @param eventType    初始化事件类型（SCHEMA_INIT/SKILLS_MD_DEFINED/MANUAL_CONFIRM/ADMIN_SET）
     * @param operatorId   操作人ID（可为 null）
     * @return 初始化后的可信度视图
     */
    ConfidenceVO initScore(Long columnMetaId, String eventType, Long operatorId);

    /**
     * 调整字段可信度分数
     * <p>
     * 根据事件类型计算分数变化量，确保分数在 0-100 范围内。
     * 每次调整记录 FieldConfidenceEvent 流水。
     * </p>
     *
     * @param columnMetaId  字段元数据ID
     * @param eventType     事件类型
     * @param operatorId    操作人ID（可为 null）
     * @param sourceQueryId 关联的查询任务ID（可为 null）
     * @return 调整后的可信度视图
     */
    ConfidenceVO adjustScore(Long columnMetaId, String eventType, Long operatorId, Long sourceQueryId);

    /**
     * 管理员手动设置可信度分数
     *
     * @param columnMetaId 字段元数据ID
     * @param score        目标分数（0-100）
     * @param reason       设置原因
     * @param operatorId   操作人ID
     * @return 设置后的可信度视图
     */
    ConfidenceVO adminSetScore(Long columnMetaId, int score, String reason, Long operatorId);
}
