package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.entity.po.IssueTransitionCount;
import com.dataocean.module.governance.entity.po.MetadataQualityIssue;
import com.dataocean.module.governance.entity.vo.QualityIssueTrendVO;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.governance.mapper.QualityIssueTrendMapper;
import com.dataocean.module.governance.service.QualityIssueTrendService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class QualityIssueTrendServiceImpl implements QualityIssueTrendService {
    private final QualityIssueTrendMapper history;
    private final MetadataQualityIssueMapper issues;

    /** 当前待处理数与增减流水必须来自同一个一致性快照，否则并发重开会重复计入。 */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QualityIssueTrendVO read(long snapshotId, int days, Collection<Long> responsibleSources) {
        if (days != 7 && days != 30) throw new BusinessException(400, "趋势区间只支持近 7 天或近 30 天");
        var window = history.observe(snapshotId);
        if (window == null) throw new BusinessException(404, "快照不存在");
        if (!responsibleSources.contains(window.datasourceId())) throw new BusinessException(403, "没有该快照的问题查看权限");
        if (window.coverageStart() == null || window.coverageStart().isAfter(window.asOf())) {
            throw new BusinessException(409, "问题历史的覆盖起点无法核实");
        }

        LocalDate today = window.asOf().toLocalDate();
        LocalDate first = today.minusDays(days - 1L);
        LocalDateTime start = first.atStartOfDay().isAfter(window.coverageStart()) ? first.atStartOfDay() : window.coverageStart();
        long balance = issues.selectCount(new LambdaQueryWrapper<MetadataQualityIssue>()
                .eq(MetadataQualityIssue::getDatasourceId, window.datasourceId())
                .eq(MetadataQualityIssue::getSnapshotId, snapshotId)
                .in(MetadataQualityIssue::getStatus, MetadataQualityIssue.UNRESOLVED_STATUSES));

        // 先汇总每一天的进出，再从真实当前余量向前倒推。观察启动前的点直接断开。
        Map<LocalDate, Flow> flows = new HashMap<>();
        for (var event : history.transitions(window.datasourceId(), snapshotId, start, window.asOf())) {
            flows.computeIfAbsent(event.eventDay(), ignored -> new Flow()).count(event);
        }
        var points = new ArrayList<QualityIssueTrendVO.Day>(days);
        for (LocalDate date = today; !date.isBefore(first); date = date.minusDays(1)) {
            if (date.isBefore(window.coverageStart().toLocalDate())) {
                points.add(new QualityIssueTrendVO.Day(date, "NONE", null, null, null, null, null));
                continue;
            }
            Flow flow = flows.getOrDefault(date, new Flow());
            String coverage = date.atStartOfDay().isBefore(window.coverageStart()) ? "PARTIAL" : "FULL";
            points.add(new QualityIssueTrendVO.Day(date, coverage, balance, flow.added, flow.reopened, flow.resolved, flow.otherClosed));
            balance += flow.resolved + flow.otherClosed - flow.added - flow.reopened;
            if (balance < 0) throw new BusinessException(409, "问题余量与历史变动不一致");
        }
        Collections.reverse(points);
        return new QualityIssueTrendVO(window.datasourceId(), snapshotId, window.asOf(), window.coverageStart(), points);
    }

    /** 待处理内部确认/重新分派不产生流量，驳回和自动关闭不增加人工解决数。 */
    private static final class Flow {
        long added, reopened, resolved, otherClosed;

        void count(IssueTransitionCount event) {
            boolean from = event.previousStatus() != null && MetadataQualityIssue.UNRESOLVED_STATUSES.contains(event.previousStatus());
            boolean to = event.nextStatus() != null && MetadataQualityIssue.UNRESOLVED_STATUSES.contains(event.nextStatus());
            if (from == to) return;
            if (to) {
                if (event.previousStatus() == null) added += event.quantity();
                else reopened += event.quantity();
            } else if (MetadataQualityIssue.STATUS_RESOLVED.equals(event.nextStatus())) {
                resolved += event.quantity();
            } else {
                otherClosed += event.quantity();
            }
        }
    }
}
