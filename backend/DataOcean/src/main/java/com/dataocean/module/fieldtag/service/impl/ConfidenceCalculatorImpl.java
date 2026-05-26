package com.dataocean.module.fieldtag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.fieldtag.entity.FieldConfidence;
import com.dataocean.module.fieldtag.entity.FieldConfidenceEvent;
import com.dataocean.module.fieldtag.entity.vo.ConfidenceVO;
import com.dataocean.module.fieldtag.mapper.FieldConfidenceEventMapper;
import com.dataocean.module.fieldtag.mapper.FieldConfidenceMapper;
import com.dataocean.module.fieldtag.service.ConfidenceCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 可信度计算引擎实现类
 * <p>
 * 核心计算逻辑：
 * - 初始分根据来源确定（Schema=30, skills.md=60, 人工=90, 管理员=100）
 * - 事件调整（查询成功+2, 点赞+10, 踩确认-15, 群体阈值-5）
 * - 分数硬边界 0-100
 * - 每次变更记录事件流水
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfidenceCalculatorImpl implements ConfidenceCalculator {

    private final FieldConfidenceMapper confidenceMapper;
    private final FieldConfidenceEventMapper eventMapper;

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConfidenceVO initScore(Long columnMetaId, String eventType, Long operatorId) {
        // 检查是否已存在可信度记录
        FieldConfidence existing = getByColumnMetaId(columnMetaId);
        if (existing != null) {
            return toVO(existing);
        }
        // 根据事件类型确定初始分
        int initScore = getInitScore(eventType);
        // 创建可信度记录
        FieldConfidence confidence = new FieldConfidence();
        confidence.setColumnMetaId(columnMetaId);
        confidence.setScore(initScore);
        confidence.setLevel(FieldConfidence.calculateLevel(initScore));
        confidence.setReason("初始化：" + eventType);
        confidence.setUpdatedAt(LocalDateTime.now());
        confidence.setUpdatedBy(operatorId);
        confidenceMapper.insert(confidence);
        // 记录事件
        recordEvent(columnMetaId, initScore, eventType, operatorId, null);
        log.info("初始化字段可信度 columnMetaId={} score={} eventType={}", columnMetaId, initScore, eventType);
        return toVO(confidence);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConfidenceVO adjustScore(Long columnMetaId, String eventType, Long operatorId, Long sourceQueryId) {
        // 获取当前可信度记录，不存在则先初始化
        FieldConfidence confidence = getByColumnMetaId(columnMetaId);
        if (confidence == null) {
            initScore(columnMetaId, FieldConfidenceEvent.TYPE_SCHEMA_INIT, operatorId);
            confidence = getByColumnMetaId(columnMetaId);
        }
        // 计算分数变化量
        int delta = getDelta(eventType);
        int oldScore = confidence.getScore();
        int newScore = Math.max(0, Math.min(100, oldScore + delta));
        // 更新可信度
        confidence.setScore(newScore);
        confidence.setLevel(FieldConfidence.calculateLevel(newScore));
        confidence.setReason(eventType + " (变化:" + delta + ")");
        confidence.setUpdatedAt(LocalDateTime.now());
        confidence.setUpdatedBy(operatorId);
        confidenceMapper.updateById(confidence);
        // 记录事件
        recordEvent(columnMetaId, delta, eventType, operatorId, sourceQueryId);
        log.info("调整字段可信度 columnMetaId={} {} → {} eventType={}", columnMetaId, oldScore, newScore, eventType);
        return toVO(confidence);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConfidenceVO adminSetScore(Long columnMetaId, int score, String reason, Long operatorId) {
        if (score < 0 || score > 100) {
            throw new BusinessException("可信度分数必须在 0-100 范围内");
        }
        // 获取或创建可信度记录
        FieldConfidence confidence = getByColumnMetaId(columnMetaId);
        int delta;
        if (confidence == null) {
            confidence = new FieldConfidence();
            confidence.setColumnMetaId(columnMetaId);
            confidence.setScore(score);
            confidence.setLevel(FieldConfidence.calculateLevel(score));
            confidence.setReason(reason != null ? reason : "管理员手动设置");
            confidence.setUpdatedAt(LocalDateTime.now());
            confidence.setUpdatedBy(operatorId);
            confidenceMapper.insert(confidence);
            delta = score;
        } else {
            delta = score - confidence.getScore();
            confidence.setScore(score);
            confidence.setLevel(FieldConfidence.calculateLevel(score));
            confidence.setReason(reason != null ? reason : "管理员手动设置");
            confidence.setUpdatedAt(LocalDateTime.now());
            confidence.setUpdatedBy(operatorId);
            confidenceMapper.updateById(confidence);
        }
        // 记录事件
        recordEvent(columnMetaId, delta, FieldConfidenceEvent.TYPE_ADMIN_SET, operatorId, null);
        log.info("管理员设置字段可信度 columnMetaId={} score={} operatorId={}", columnMetaId, score, operatorId);
        return toVO(confidence);
    }

    /**
     * 根据事件类型获取初始分
     */
    private int getInitScore(String eventType) {
        return switch (eventType) {
            case FieldConfidenceEvent.TYPE_SCHEMA_INIT -> INIT_SCORE_SCHEMA;
            case FieldConfidenceEvent.TYPE_SKILLS_MD_DEFINED -> INIT_SCORE_SKILLS_MD;
            case FieldConfidenceEvent.TYPE_MANUAL_CONFIRM -> INIT_SCORE_MANUAL;
            case FieldConfidenceEvent.TYPE_ADMIN_SET -> INIT_SCORE_ADMIN;
            default -> INIT_SCORE_SCHEMA;
        };
    }

    /**
     * 根据事件类型获取分数变化量
     */
    private int getDelta(String eventType) {
        return switch (eventType) {
            case FieldConfidenceEvent.TYPE_QUERY_SUCCESS -> DELTA_QUERY_SUCCESS;
            case FieldConfidenceEvent.TYPE_USER_LIKE -> DELTA_USER_LIKE;
            case FieldConfidenceEvent.TYPE_USER_DISLIKE_CONFIRMED -> DELTA_USER_DISLIKE_CONFIRMED;
            case FieldConfidenceEvent.TYPE_GROUP_THRESHOLD -> DELTA_GROUP_THRESHOLD;
            default -> 0;
        };
    }

    /**
     * 根据字段ID查询可信度记录
     */
    private FieldConfidence getByColumnMetaId(Long columnMetaId) {
        return confidenceMapper.selectOne(
                new LambdaQueryWrapper<FieldConfidence>()
                        .eq(FieldConfidence::getColumnMetaId, columnMetaId)
        );
    }

    /**
     * 记录可信度变更事件
     */
    private void recordEvent(Long columnMetaId, int delta, String eventType, Long operatorId, Long sourceQueryId) {
        FieldConfidenceEvent event = new FieldConfidenceEvent();
        event.setColumnMetaId(columnMetaId);
        event.setDeltaScore(delta);
        event.setEventType(eventType);
        event.setOperatorId(operatorId);
        event.setSourceQueryId(sourceQueryId);
        event.setCreatedAt(LocalDateTime.now());
        eventMapper.insert(event);
    }

    /**
     * 实体转视图对象
     */
    private ConfidenceVO toVO(FieldConfidence confidence) {
        ConfidenceVO vo = new ConfidenceVO();
        vo.setColumnMetaId(confidence.getColumnMetaId());
        vo.setScore(confidence.getScore());
        vo.setLevel(confidence.getLevel());
        vo.setReason(confidence.getReason());
        vo.setLastUpdated(confidence.getUpdatedAt());
        return vo;
    }
}
