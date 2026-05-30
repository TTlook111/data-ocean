package com.dataocean.module.fieldtag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.fieldtag.entity.FieldConfidence;
import com.dataocean.module.fieldtag.entity.FieldConfidenceEvent;
import com.dataocean.module.fieldtag.entity.vo.ConfidenceEventVO;
import com.dataocean.module.fieldtag.entity.vo.ConfidenceVO;
import com.dataocean.module.fieldtag.mapper.FieldConfidenceEventMapper;
import com.dataocean.module.fieldtag.mapper.FieldConfidenceMapper;
import com.dataocean.module.fieldtag.service.ConfidenceCalculator;
import com.dataocean.module.fieldtag.service.FieldConfidenceService;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
    private final DbColumnMetaMapper dbColumnMetaMapper;

    /**
     * {@inheritDoc}
     */
    @Override
    public Page<ConfidenceVO> pageConfidence(int page, int pageSize, String level, Long datasourceId) {
        // 构建查询条件：按等级过滤，按分数降序展示
        LambdaQueryWrapper<FieldConfidence> wrapper = new LambdaQueryWrapper<FieldConfidence>()
                .eq(level != null && !level.isBlank(), FieldConfidence::getLevel, level)
                .orderByDesc(FieldConfidence::getScore)
                .orderByDesc(FieldConfidence::getUpdatedAt);

        // 若指定数据源，先查出该数据源下的字段 ID 集合再过滤（可信度表无 datasourceId 字段）
        if (datasourceId != null) {
            List<Long> columnIds = dbColumnMetaMapper.selectList(
                            new LambdaQueryWrapper<DbColumnMeta>()
                                    .eq(DbColumnMeta::getDatasourceId, datasourceId)
                                    .select(DbColumnMeta::getId))
                    .stream().map(DbColumnMeta::getId).collect(Collectors.toList());
            if (columnIds.isEmpty()) {
                // 该数据源下没有任何字段，直接返回空分页
                return new Page<>(page, pageSize, 0);
            }
            wrapper.in(FieldConfidence::getColumnMetaId, columnIds);
        }

        Page<FieldConfidence> confidencePage = confidenceMapper.selectPage(new Page<>(page, pageSize), wrapper);

        // 批量补全字段名与表名，避免逐行查询
        List<Long> columnMetaIds = confidencePage.getRecords().stream()
                .map(FieldConfidence::getColumnMetaId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, DbColumnMeta> columnMetaMap = columnMetaIds.isEmpty()
                ? Collections.emptyMap()
                : dbColumnMetaMapper.selectBatchIds(columnMetaIds).stream()
                        .collect(Collectors.toMap(DbColumnMeta::getId, Function.identity(), (a, b) -> a));

        Page<ConfidenceVO> resultPage = new Page<>(page, pageSize, confidencePage.getTotal());
        List<ConfidenceVO> voList = confidencePage.getRecords().stream()
                .map(c -> {
                    ConfidenceVO vo = toVO(c);
                    DbColumnMeta column = columnMetaMap.get(c.getColumnMetaId());
                    if (column != null) {
                        vo.setColumnName(column.getColumnName());
                        vo.setTableName(column.getTableName());
                    }
                    return vo;
                })
                .collect(Collectors.toList());
        resultPage.setRecords(voList);
        return resultPage;
    }

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
