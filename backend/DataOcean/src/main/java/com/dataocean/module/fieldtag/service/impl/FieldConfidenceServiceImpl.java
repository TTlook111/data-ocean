package com.dataocean.module.fieldtag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.fieldtag.entity.FieldConfidence;
import com.dataocean.module.fieldtag.entity.FieldConfidenceEvent;
import com.dataocean.module.fieldtag.entity.vo.ConfidenceEventVO;
import com.dataocean.module.fieldtag.entity.vo.ConfidenceVO;
import com.dataocean.module.fieldtag.mapper.FieldConfidenceEventMapper;
import com.dataocean.module.fieldtag.mapper.FieldConfidenceMapper;
import com.dataocean.module.fieldtag.service.ConfidenceCalculator;
import com.dataocean.module.fieldtag.service.FieldConfidenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 字段可信度服务实现类
 * <p>
 * 提供可信度查询和管理员设置功能，调整逻辑委托给 ConfidenceCalculator。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FieldConfidenceServiceImpl implements FieldConfidenceService {

    private final FieldConfidenceMapper confidenceMapper;
    private final FieldConfidenceEventMapper eventMapper;
    private final ConfidenceCalculator confidenceCalculator;

    /**
     * {@inheritDoc}
     */
    @Override
    public ConfidenceVO getConfidence(Long columnMetaId) {
        FieldConfidence confidence = confidenceMapper.selectOne(
                new LambdaQueryWrapper<FieldConfidence>()
                        .eq(FieldConfidence::getColumnMetaId, columnMetaId)
        );
        if (confidence == null) {
            return null;
        }
        return toVO(confidence);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ConfidenceVO> batchGetConfidence(List<Long> columnMetaIds) {
        if (columnMetaIds == null || columnMetaIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<FieldConfidence> confidences = confidenceMapper.selectList(
                new LambdaQueryWrapper<FieldConfidence>()
                        .in(FieldConfidence::getColumnMetaId, columnMetaIds)
        );
        return confidences.stream().map(this::toVO).collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ConfidenceVO adminSetScore(Long columnMetaId, int score, String reason) {
        Long operatorId = UserContext.currentUserId();
        return confidenceCalculator.adminSetScore(columnMetaId, score, reason, operatorId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ConfidenceEventVO> getEventHistory(Long columnMetaId) {
        List<FieldConfidenceEvent> events = eventMapper.selectList(
                new LambdaQueryWrapper<FieldConfidenceEvent>()
                        .eq(FieldConfidenceEvent::getColumnMetaId, columnMetaId)
                        .orderByDesc(FieldConfidenceEvent::getCreatedAt)
        );
        return events.stream().map(this::toEventVO).collect(Collectors.toList());
    }

    /**
     * 实体转可信度视图对象
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

    /**
     * 实体转事件视图对象
     */
    private ConfidenceEventVO toEventVO(FieldConfidenceEvent event) {
        ConfidenceEventVO vo = new ConfidenceEventVO();
        BeanUtils.copyProperties(event, vo);
        return vo;
    }
}
