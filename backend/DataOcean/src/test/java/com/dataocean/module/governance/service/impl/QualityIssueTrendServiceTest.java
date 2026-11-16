package com.dataocean.module.governance.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.entity.po.IssueTransitionCount;
import com.dataocean.module.governance.entity.po.IssueTrendObservation;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.governance.mapper.QualityIssueTrendMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QualityIssueTrendServiceTest {
    private final QualityIssueTrendMapper history = mock(QualityIssueTrendMapper.class);
    private final MetadataQualityIssueMapper issues = mock(MetadataQualityIssueMapper.class);
    private final QualityIssueTrendServiceImpl service = new QualityIssueTrendServiceImpl(history, issues);
    private final LocalDateTime start = LocalDateTime.of(2026, 11, 5, 14, 0);
    private final LocalDateTime asOf = start.plusDays(2);

    // 手工例：启用时已有 4 个。首日 4+2-1=5，次日 5+1-2=4，今天无变化。
    @Test void reconstructsFromCurrentBalanceAndAtomicMovements() {
        when(history.observe(7)).thenReturn(new IssueTrendObservation(3L, start, asOf));
        when(issues.selectCount(any())).thenReturn(4L);
        when(history.transitions(3, 7, start, asOf)).thenReturn(List.of(
                new IssueTransitionCount(start.toLocalDate(), null, "OPEN", 2),
                new IssueTransitionCount(start.toLocalDate(), "CONFIRMED", "RESOLVED", 1),
                new IssueTransitionCount(start.toLocalDate(), "OPEN", "CONFIRMED", 3),
                new IssueTransitionCount(start.plusDays(1).toLocalDate(), "RESOLVED", "REOPENED", 1),
                new IssueTransitionCount(start.plusDays(1).toLocalDate(), "REOPENED", "AUTO_CLOSED", 1),
                new IssueTransitionCount(start.plusDays(1).toLocalDate(), "OPEN", "REJECTED", 1)));
        var days = service.read(7, 7, List.of(3L)).days();
        assertThat(days).hasSize(7);
        assertThat(days.subList(0,4)).allMatch(d -> d.coverage().equals("NONE") && d.remaining() == null && d.added() == null);
        assertThat(days.get(4)).satisfies(d -> {
            assertThat(d.coverage()).isEqualTo("PARTIAL");
            assertThat(List.of(d.remaining(), d.added(), d.resolved())).containsExactly(5L,2L,1L);
        });
        assertThat(days.get(5)).satisfies(d -> {
            assertThat(d.coverage()).isEqualTo("FULL");
            assertThat(List.of(d.remaining(), d.reopened(), d.resolved(), d.otherClosed())).containsExactly(4L,1L,0L,2L);
        });
        assertThat(days.get(6).remaining()).isEqualTo(4);
        assertThat(days.get(6).added()).isZero();
    }

    // 已覆盖且确实没有问题才填零；以实际业务日窗口而不是流水首日决定缺口。
    @Test void zeroDaysExistWhenCoveredBeforeTheWindow() {
        when(history.observe(7)).thenReturn(new IssueTrendObservation(3L, start.minusMonths(2), asOf));
        when(issues.selectCount(any())).thenReturn(0L);
        var result = service.read(7, 30, List.of(3L));
        assertThat(result.days()).hasSize(30).allMatch(d -> d.coverage().equals("FULL") && d.remaining() == 0);
        verify(history).transitions(3L,7L,asOf.toLocalDate().minusDays(29).atStartOfDay(),asOf);
    }

    // 非法区间与源外访问均不能继续查询问题计数，也不复用其他源的历史。
    @Test void rejectsInvalidRangeAndForeignSnapshot() {
        assertThatThrownBy(() -> service.read(7, 200, List.of(3L))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(history);
        when(history.observe(7)).thenReturn(new IssueTrendObservation(3L,start,asOf));
        assertThatThrownBy(() -> service.read(7, 7, List.of(8L))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(issues);
        verify(history,never()).transitions(anyLong(),anyLong(),any(),any());
    }
}
