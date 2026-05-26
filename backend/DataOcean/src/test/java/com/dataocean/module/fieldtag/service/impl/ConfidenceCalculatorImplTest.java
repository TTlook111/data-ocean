package com.dataocean.module.fieldtag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.fieldtag.entity.FieldConfidence;
import com.dataocean.module.fieldtag.entity.FieldConfidenceEvent;
import com.dataocean.module.fieldtag.entity.vo.ConfidenceVO;
import com.dataocean.module.fieldtag.mapper.FieldConfidenceEventMapper;
import com.dataocean.module.fieldtag.mapper.FieldConfidenceMapper;
import com.dataocean.module.fieldtag.service.ConfidenceCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 可信度计算引擎单元测试
 */
@ExtendWith(MockitoExtension.class)
class ConfidenceCalculatorImplTest {

    @Mock
    private FieldConfidenceMapper confidenceMapper;

    @Mock
    private FieldConfidenceEventMapper eventMapper;

    private ConfidenceCalculatorImpl calculator;

    @BeforeEach
    void setUp() {
        calculator = new ConfidenceCalculatorImpl(confidenceMapper, eventMapper);
    }

    @Test
    void initScore_schemaInit_shouldReturn30() {
        when(confidenceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(confidenceMapper.insert(isA(FieldConfidence.class))).thenReturn(1);
        when(eventMapper.insert(isA(FieldConfidenceEvent.class))).thenReturn(1);

        ConfidenceVO result = calculator.initScore(1L, FieldConfidenceEvent.TYPE_SCHEMA_INIT, null);

        assertThat(result.getScore()).isEqualTo(30);
        assertThat(result.getLevel()).isEqualTo("LOW");
    }

    @Test
    void initScore_skillsMdDefined_shouldReturn60() {
        when(confidenceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(confidenceMapper.insert(isA(FieldConfidence.class))).thenReturn(1);
        when(eventMapper.insert(isA(FieldConfidenceEvent.class))).thenReturn(1);

        ConfidenceVO result = calculator.initScore(1L, FieldConfidenceEvent.TYPE_SKILLS_MD_DEFINED, null);

        assertThat(result.getScore()).isEqualTo(60);
        assertThat(result.getLevel()).isEqualTo("MEDIUM");
    }

    @Test
    void initScore_manualConfirm_shouldReturn90() {
        when(confidenceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(confidenceMapper.insert(isA(FieldConfidence.class))).thenReturn(1);
        when(eventMapper.insert(isA(FieldConfidenceEvent.class))).thenReturn(1);

        ConfidenceVO result = calculator.initScore(1L, FieldConfidenceEvent.TYPE_MANUAL_CONFIRM, 100L);

        assertThat(result.getScore()).isEqualTo(90);
        assertThat(result.getLevel()).isEqualTo("HIGH");
    }

    @Test
    void initScore_alreadyExists_shouldReturnExisting() {
        FieldConfidence existing = new FieldConfidence();
        existing.setColumnMetaId(1L);
        existing.setScore(50);
        existing.setLevel("MEDIUM");
        when(confidenceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        ConfidenceVO result = calculator.initScore(1L, FieldConfidenceEvent.TYPE_SCHEMA_INIT, null);

        assertThat(result.getScore()).isEqualTo(50);
    }

    @Test
    void adjustScore_userLike_shouldAdd10() {
        FieldConfidence existing = buildConfidence(50);
        when(confidenceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(confidenceMapper.updateById(isA(FieldConfidence.class))).thenReturn(1);
        when(eventMapper.insert(isA(FieldConfidenceEvent.class))).thenReturn(1);

        ConfidenceVO result = calculator.adjustScore(1L, FieldConfidenceEvent.TYPE_USER_LIKE, 100L, null);

        assertThat(result.getScore()).isEqualTo(60);
        assertThat(result.getLevel()).isEqualTo("MEDIUM");
    }

    @Test
    void adjustScore_querySuccess_shouldAdd2() {
        FieldConfidence existing = buildConfidence(68);
        when(confidenceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(confidenceMapper.updateById(isA(FieldConfidence.class))).thenReturn(1);
        when(eventMapper.insert(isA(FieldConfidenceEvent.class))).thenReturn(1);

        ConfidenceVO result = calculator.adjustScore(1L, FieldConfidenceEvent.TYPE_QUERY_SUCCESS, null, 999L);

        assertThat(result.getScore()).isEqualTo(70);
        assertThat(result.getLevel()).isEqualTo("HIGH");
    }

    @Test
    void adjustScore_userDislikeConfirmed_shouldSubtract15() {
        FieldConfidence existing = buildConfidence(60);
        when(confidenceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(confidenceMapper.updateById(isA(FieldConfidence.class))).thenReturn(1);
        when(eventMapper.insert(isA(FieldConfidenceEvent.class))).thenReturn(1);

        ConfidenceVO result = calculator.adjustScore(1L, FieldConfidenceEvent.TYPE_USER_DISLIKE_CONFIRMED, 100L, null);

        assertThat(result.getScore()).isEqualTo(45);
        assertThat(result.getLevel()).isEqualTo("MEDIUM");
    }

    @Test
    void adjustScore_groupThreshold_shouldSubtract5() {
        FieldConfidence existing = buildConfidence(42);
        when(confidenceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(confidenceMapper.updateById(isA(FieldConfidence.class))).thenReturn(1);
        when(eventMapper.insert(isA(FieldConfidenceEvent.class))).thenReturn(1);

        ConfidenceVO result = calculator.adjustScore(1L, FieldConfidenceEvent.TYPE_GROUP_THRESHOLD, null, null);

        assertThat(result.getScore()).isEqualTo(37);
        assertThat(result.getLevel()).isEqualTo("LOW");
    }

    @Test
    void adjustScore_shouldNotExceed100() {
        FieldConfidence existing = buildConfidence(95);
        when(confidenceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(confidenceMapper.updateById(isA(FieldConfidence.class))).thenReturn(1);
        when(eventMapper.insert(isA(FieldConfidenceEvent.class))).thenReturn(1);

        ConfidenceVO result = calculator.adjustScore(1L, FieldConfidenceEvent.TYPE_USER_LIKE, 100L, null);

        assertThat(result.getScore()).isEqualTo(100);
    }

    @Test
    void adjustScore_shouldNotGoBelowZero() {
        FieldConfidence existing = buildConfidence(3);
        when(confidenceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(confidenceMapper.updateById(isA(FieldConfidence.class))).thenReturn(1);
        when(eventMapper.insert(isA(FieldConfidenceEvent.class))).thenReturn(1);

        ConfidenceVO result = calculator.adjustScore(1L, FieldConfidenceEvent.TYPE_USER_DISLIKE_CONFIRMED, 100L, null);

        assertThat(result.getScore()).isEqualTo(0);
    }

    @Test
    void adjustScore_shouldRecordEvent() {
        FieldConfidence existing = buildConfidence(50);
        when(confidenceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(confidenceMapper.updateById(isA(FieldConfidence.class))).thenReturn(1);
        when(eventMapper.insert(isA(FieldConfidenceEvent.class))).thenReturn(1);

        calculator.adjustScore(1L, FieldConfidenceEvent.TYPE_USER_LIKE, 100L, 999L);

        ArgumentCaptor<FieldConfidenceEvent> captor = ArgumentCaptor.forClass(FieldConfidenceEvent.class);
        verify(eventMapper).insert(captor.capture());
        FieldConfidenceEvent event = captor.getValue();
        assertThat(event.getColumnMetaId()).isEqualTo(1L);
        assertThat(event.getDeltaScore()).isEqualTo(10);
        assertThat(event.getEventType()).isEqualTo(FieldConfidenceEvent.TYPE_USER_LIKE);
        assertThat(event.getOperatorId()).isEqualTo(100L);
        assertThat(event.getSourceQueryId()).isEqualTo(999L);
    }

    @Test
    void adminSetScore_shouldSetExactScore() {
        FieldConfidence existing = buildConfidence(30);
        when(confidenceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(confidenceMapper.updateById(isA(FieldConfidence.class))).thenReturn(1);
        when(eventMapper.insert(isA(FieldConfidenceEvent.class))).thenReturn(1);

        ConfidenceVO result = calculator.adminSetScore(1L, 85, "管理员手动提升", 100L);

        assertThat(result.getScore()).isEqualTo(85);
        assertThat(result.getLevel()).isEqualTo("HIGH");
        assertThat(result.getReason()).isEqualTo("管理员手动提升");
    }

    @Test
    void adminSetScore_newField_shouldCreateRecord() {
        when(confidenceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(confidenceMapper.insert(isA(FieldConfidence.class))).thenReturn(1);
        when(eventMapper.insert(isA(FieldConfidenceEvent.class))).thenReturn(1);

        ConfidenceVO result = calculator.adminSetScore(1L, 100, "直接设定", 100L);

        assertThat(result.getScore()).isEqualTo(100);
        assertThat(result.getLevel()).isEqualTo("HIGH");
    }

    @Test
    void adminSetScore_invalidScore_shouldThrow() {
        assertThatThrownBy(() -> calculator.adminSetScore(1L, 101, "无效", 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("0-100");

        assertThatThrownBy(() -> calculator.adminSetScore(1L, -1, "无效", 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("0-100");
    }

    @Test
    void levelCalculation_boundaries() {
        assertThat(FieldConfidence.calculateLevel(100)).isEqualTo("HIGH");
        assertThat(FieldConfidence.calculateLevel(70)).isEqualTo("HIGH");
        assertThat(FieldConfidence.calculateLevel(69)).isEqualTo("MEDIUM");
        assertThat(FieldConfidence.calculateLevel(40)).isEqualTo("MEDIUM");
        assertThat(FieldConfidence.calculateLevel(39)).isEqualTo("LOW");
        assertThat(FieldConfidence.calculateLevel(0)).isEqualTo("LOW");
    }

    private FieldConfidence buildConfidence(int score) {
        FieldConfidence c = new FieldConfidence();
        c.setId(1L);
        c.setColumnMetaId(1L);
        c.setScore(score);
        c.setLevel(FieldConfidence.calculateLevel(score));
        return c;
    }
}
