package com.dataocean.module.knowledge.service.impl;

import com.dataocean.common.Ids;
import com.dataocean.common.PageVO;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.overview.OverviewPeriod;
import com.dataocean.common.overview.OverviewPeriod.Window;
import com.dataocean.common.overview.OverviewQuery;
import com.dataocean.common.overview.OverviewSourceVO;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.knowledge.entity.vo.KnowledgeOverviewVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeOverviewVO.Detail;
import com.dataocean.module.knowledge.entity.vo.KnowledgeOverviewVO.Point;
import com.dataocean.module.knowledge.mapper.KnowledgeOverviewMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeOverviewMapper.Activity;
import com.dataocean.module.knowledge.mapper.KnowledgeOverviewMapper.Volume;
import com.dataocean.module.knowledge.service.KnowledgeOverviewService;
import com.dataocean.module.knowledge.service.impl.KnowledgeOverviewHistory.DocumentState;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 统计和下钻共享截止时刻、事实读取及角色核验。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeOverviewServiceImpl implements KnowledgeOverviewService {

    /** 四类期末文档指标。 */
    private static final Set<String> STOCK = Set.of("AVAILABLE", "UPDATING", "PAUSED", "FORBIDDEN");

    /** 已核实发布和两类逐题异常。 */
    private static final Set<String> ACTIVITY = Set.of("PUBLISHED", "FAILED", "EXECUTION_ERROR");

    /** 角色和账号状态的共同读边界。 */
    private final GuardMapper guard;

    /** 区分看板、文档及发布明细查看权。 */
    private final PermissionService permissions;

    /** 可回看的原始发布及验证事实。 */
    private final KnowledgeOverviewMapper mapper;

    /** 同一时点的真实正式文档状态。 */
    private final KnowledgeOverviewHistory history;

    /**
     * 发生量按时间分组，存量使用每个分组的结束时点。
     */
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public KnowledgeOverviewVO overview(OverviewQuery query) {
        guard.shared();
        permissions.require("knowledge-dashboard.view");
        var options = mapper.sources();
        Long source = selectedSource(query, options);
        var now = mapper.now();
        var period = OverviewPeriod.resolve(query, now, true);
        if (options.isEmpty()) {
            // 区分尚无来源与已有来源的零文档。
            return new KnowledgeOverviewVO(options, Times.instant(period.asOf()), period.grain(), List.of(), null);
        }
        var bounds = period.bounds();
        var windows = period.windowsWithTotal();
        var activity = mapper.totals(source, bounds.start(), bounds.end(), period.asOf(), windows);
        var stock = history.evaluate(source, windows, now);
        var points = activity.stream()
                .map(row -> point(windows.get(row.ordinal()), row, stock.get(row.ordinal())))
                .toList();
        int last = points.size() - 1;
        return new KnowledgeOverviewVO(
                options, Times.instant(period.asOf()), period.grain(), points.subList(0, last), points.get(last));
    }

    /**
     * 下钻只读取具备目标明细权限的事实。
     */
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PageVO<Detail> details(OverviewQuery query) {
        guard.shared();
        permissions.require("knowledge-dashboard.view");
        String metric = query.getMetric();
        if (metric == null || !STOCK.contains(metric) && !ACTIVITY.contains(metric)) {
            // 只接受界面已定义的可对账分类。
            throw BizIllegalException.invalid("无效的知识统计指标");
        }
        boolean stock = STOCK.contains(metric);
        permissions.require(stock ? "knowledge.view" : "releases.view");
        Long source = selectedSource(query, mapper.sources());
        var now = mapper.now();
        var period = OverviewPeriod.resolve(query, now, true);
        var bounds = period.bounds();
        if (stock) {
            // 期末明细共享同一份有效绑定和历史证明。
            var result = history.evaluate(source, List.of(bounds), now).get(bounds.index());
            return stockPage(query, bounds, result, metric);
        }
        var page = query.<Activity>toFixedOrderMpPage();
        page.setOptimizeCountSql(false);
        return PageVO.from(
                mapper.details(page, source, bounds.start(), bounds.end(), period.asOf(), metric),
                row -> new Detail(
                        row.id(),
                        Ids.text(row.sourceId()),
                        row.sourceName(),
                        null,
                        null,
                        Ids.text(row.taskId()),
                        row.runId(),
                        row.title(),
                        row.kind(),
                        Times.instant(row.occurredAt())));
    }

    /**
     * 可用性证据不足的窗口保留空值，维护三类使用各自的明确条件。
     */
    private Point point(Window window, Volume row, List<DocumentState> documents) {
        Long available = documents.stream().anyMatch(doc -> doc.available() == null)
                ? null
                : documents.stream()
                        .filter(doc -> Boolean.TRUE.equals(doc.available()))
                        .count();
        return new Point(
                window.fromDate(),
                window.toDate(),
                available,
                row.published(),
                row.failed(),
                row.errors(),
                documents.stream().filter(doc -> doc.state().updating()).count(),
                documents.stream().filter(doc -> doc.state().paused()).count(),
                documents.stream().filter(doc -> doc.state().forbidden()).count());
    }

    /**
     * 对确定可核对的期末集合分页，超页回到最后一页。
     */
    private PageVO<Detail> stockPage(OverviewQuery query, Window bounds, List<DocumentState> documents, String metric) {
        if ("AVAILABLE".equals(metric) && documents.stream().anyMatch(doc -> doc.available() == null)) {
            // 空缺不是部分可用清单。
            throw BizIllegalException.state("所选期末的可用性依据不完整");
        }
        var matching = documents.stream()
                .filter(doc -> {
                    // 维护标记独立筛选，文档可在多个分类出现。
                    return switch (metric) {
                        case "AVAILABLE" -> Boolean.TRUE.equals(doc.available());
                        case "UPDATING" -> doc.state().updating();
                        case "PAUSED" -> doc.state().paused();
                        default -> doc.state().forbidden();
                    };
                })
                .toList();
        var page = query.<DocumentState>toFixedOrderMpPage();
        page.setTotal(matching.size());
        page.setCurrent(Math.min(page.getCurrent(), Math.max(1, page.getPages())));
        int begin = Math.toIntExact(page.offset());
        int end = (int) Math.min(begin + page.getSize(), matching.size());
        page.setRecords(matching.subList(begin, end));
        return PageVO.from(page, row -> {
            // 文档身份和绑定标题都来自对应期末。
            var state = row.state();
            return new Detail(
                    Ids.text(state.documentId()),
                    Ids.text(state.sourceId()),
                    state.sourceName(),
                    Ids.text(state.documentId()),
                    Ids.text(state.revisionId()),
                    null,
                    null,
                    state.title(),
                    metric,
                    Times.instant(bounds.end()));
        });
    }

    /**
     * 显式来源须属于当前后台可统计范围。
     */
    private Long selectedSource(OverviewQuery query, List<OverviewSourceVO> options) {
        Long source = Ids.nullable(query.getSourceId());
        if (source != null && options.stream().noneMatch(item -> item.getId().equals(Ids.text(source)))) {
            // 无效编号不能扩大为全部来源。
            throw BizIllegalException.missing();
        }
        return source;
    }
}
