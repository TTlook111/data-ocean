package com.dataocean.module.governance.service.impl;

import com.dataocean.common.Ids;
import com.dataocean.common.PageVO;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.overview.OverviewPeriod;
import com.dataocean.common.overview.OverviewPeriod.Window;
import com.dataocean.common.overview.OverviewQuery;
import com.dataocean.common.overview.OverviewSourceVO;
import com.dataocean.module.governance.entity.vo.GovernanceOverviewVO;
import com.dataocean.module.governance.entity.vo.GovernanceOverviewVO.Detail;
import com.dataocean.module.governance.entity.vo.GovernanceOverviewVO.Point;
import com.dataocean.module.governance.mapper.GovernanceOverviewMapper;
import com.dataocean.module.governance.mapper.GovernanceOverviewMapper.Counts;
import com.dataocean.module.governance.mapper.GovernanceOverviewMapper.DetailRow;
import com.dataocean.module.governance.mapper.GovernanceProgressMapper;
import com.dataocean.module.governance.mapper.GovernanceProgressMapper.Progress;
import com.dataocean.module.governance.service.GovernanceOverviewService;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.service.PermissionService;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 在一致读快照中取得确认趋势、待核对和期末治理进度。
 */
@Service
@RequiredArgsConstructor
public class GovernanceOverviewServiceImpl implements GovernanceOverviewService {

    /**
     * 只接受图表能核对的事件和存量分类。
     */
    private static final Set<String> METRICS =
            Set.of("TABLE", "COLUMN", "RELATION", "METRIC", "RECHECK", "COMPLETE", "IN_PROGRESS", "UNSTARTED");

    /**
     * 三类对象进度通过期末目录读取。
     */
    private static final Set<String> PROGRESS = Set.of("COMPLETE", "IN_PROGRESS", "UNSTARTED");

    /**
     * 与权限和账号变化出入同一权限保护边界。
     */
    private final GuardMapper guard;

    /**
     * 汇总与原记录的当前权限。
     */
    private final PermissionService permissions;

    /**
     * 确认与复核事实读取。
     */
    private final GovernanceOverviewMapper mapper;

    /**
     * 针对期末读取原目录范围和有效确认。
     */
    private final GovernanceProgressMapper progressMapper;

    /**
     * 趋势使用各分组，治理进度环图只计算所选期末。
     */
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public GovernanceOverviewVO overview(OverviewQuery query) {
        guard.shared();
        permissions.require("governance-dashboard.view");
        var options = mapper.sources();
        var period = OverviewPeriod.resolve(query, mapper.now(), true);
        Long source = selectedSource(query, options);
        if (options.isEmpty()) {
            // 无来源时通过独立空态展示，不产生虚构的零观测。
            return new GovernanceOverviewVO(options, Times.instant(period.asOf()), period.grain(), List.of(), null);
        }
        var bounds = period.bounds();
        var windows = period.windowsWithTotal();
        var totals = mapper.totals(source, bounds.start(), bounds.end(), windows);
        var progress = progressMapper.total(source, bounds.end());
        var points = totals.stream()
                .map(row -> {
                    // 趋势只需要确认与复核存量，环图数据只附在全期行。
                    return point(
                            windows.get(row.ordinal()), row, row.ordinal() == windows.size() - 1 ? progress : null);
                })
                .toList();
        int last = points.size() - 1;
        return new GovernanceOverviewVO(
                options, Times.instant(period.asOf()), period.grain(), points.subList(0, last), points.get(last));
    }

    /**
     * 下钻按原报告截止点分页，重新核对调用者的业务查看权限。
     */
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PageVO<Detail> details(OverviewQuery query) {
        guard.shared();
        permissions.require("governance-dashboard.view");
        permissions.require("governance.view");
        String metric = query.getMetric();
        if (metric == null || !METRICS.contains(metric)) {
            // 未受支持的分类不能当作空结果。
            throw BizIllegalException.invalid("无效的治理统计指标");
        }
        Long source = selectedSource(query, mapper.sources());
        var bounds = OverviewPeriod.resolve(query, mapper.now(), true).bounds();
        var page = query.<DetailRow>toFixedOrderMpPage();
        // 对完整的历史 CTE 计数，使明细与汇总覆盖同一批记录。
        page.setOptimizeCountSql(false);
        var rows = PROGRESS.contains(metric)
                ? progressMapper.details(page, source, bounds.end(), metric)
                : mapper.details(page, source, bounds.start(), bounds.end(), metric);
        return PageVO.from(
                rows,
                row -> new Detail(
                        row.id(),
                        Ids.text(row.sourceId()),
                        row.sourceName(),
                        Ids.text(row.objectId()),
                        Ids.text(row.targetId()),
                        row.kind(),
                        row.label(),
                        Times.instant(row.occurredAt())));
    }

    /**
     * 使用后台治理的现有来源选择，未知来源与其他业务读取一致。
     */
    private Long selectedSource(OverviewQuery query, List<OverviewSourceVO> options) {
        Long source = Ids.nullable(query.getSourceId());
        if (source != null && options.stream().noneMatch(item -> item.getId().equals(Ids.text(source)))) {
            // 显式来源不能隐式改成全部来源。
            throw BizIllegalException.missing();
        }
        return source;
    }

    /**
     * 按窗口的业务日期标注计数和存量。
     */
    private Point point(Window window, Counts row, Progress progress) {
        return new Point(
                window.fromDate(),
                window.toDate(),
                row.tableConfirmed(),
                row.fieldConfirmed(),
                row.relationConfirmed(),
                row.metricConfirmed(),
                row.pendingReview(),
                progress == null ? null : progress.complete(),
                progress == null ? null : progress.inProgress(),
                progress == null ? null : progress.unstarted());
    }
}
