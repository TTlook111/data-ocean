package com.dataocean.module.dashboard.service.impl;

import com.dataocean.common.Ids;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.overview.OverviewPeriod;
import com.dataocean.common.overview.OverviewQuery;
import com.dataocean.common.overview.OverviewSourceVO;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.dashboard.entity.vo.DashboardOverviewVO;
import com.dataocean.module.dashboard.entity.vo.DashboardOverviewVO.Point;
import com.dataocean.module.dashboard.mapper.DashboardOverviewMapper;
import com.dataocean.module.dashboard.service.DashboardOverviewService;
import com.dataocean.module.datasource.service.SourceOverviewService;
import com.dataocean.module.feedback.service.FeedbackOverviewService;
import com.dataocean.module.governance.service.GovernanceOverviewService;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.knowledge.service.KnowledgeOverviewService;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 在同一权限快照和统计时点组合五张总览图，不在此重算模块指标。
 */
@Service
@RequiredArgsConstructor
public class DashboardOverviewServiceImpl implements DashboardOverviewService {

    /**
     * 共享权限锁覆盖选项、汇总及模块调用。
     */
    private final GuardMapper guard;

    /**
     * 分别判断总览入口和各模块的聚合权限。
     */
    private final PermissionService permissions;

    /**
     * 问数使用当前有效的源访问范围，与反馈概览一致。
     */
    private final AccessScopeService access;

    /**
     * 问数运营统计及总览使用的平台时钟。
     */
    private final DashboardOverviewMapper mapper;

    /**
     * 复用采集终态聚合。
     */
    private final SourceOverviewService sources;

    /**
     * 复用治理确认事实。
     */
    private final GovernanceOverviewService governance;

    /**
     * 复用知识期末可用性判断。
     */
    private final KnowledgeOverviewService knowledge;

    /**
     * 复用反馈新增和实际解决的事件口径。
     */
    private final FeedbackOverviewService feedback;

    /**
     * 冻结当前源范围和截止时刻后，只读取账号获准的模块统计。
     */
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public DashboardOverviewVO overview(OverviewQuery query) {
        guard.shared();
        permissions.require("dashboard.view");

        var period = OverviewPeriod.resolve(query, mapper.now(), true);
        var asOf = Times.instant(period.asOf());
        boolean canReadSources = permissions.has("sources-dashboard.view");
        boolean canReadGovernance = permissions.has("governance-dashboard.view");
        boolean canReadKnowledge = permissions.has("knowledge-dashboard.view");
        boolean canReadFeedback = permissions.has("feedback-dashboard.view");

        var authorized = access.current(UserContext.getUser()).stream()
                // 只返回统计筛选需要的标签，原访问规则不进入总览。
                .map(scope -> new OverviewSourceVO(
                        scope.getSource().getId(), scope.getSource().getName()))
                .toList();
        var options = canReadSources || canReadGovernance || canReadKnowledge ? mapper.sources() : authorized;
        String source = Ids.text(Ids.nullable(query.getSourceId()));
        if (source != null
                && options.stream().noneMatch(option -> option.getId().equals(source))) {
            // 对不存在的编号和不再可见的编号使用同一反馈。
            throw BizIllegalException.missing();
        }
        if (options.isEmpty()) {
            // 无统计范围时不创建样本，也不伪造真实零值。
            return new DashboardOverviewVO(options, asOf, period.grain(), List.of(), null, Set.of(), 0);
        }

        var scopedIds = authorized.stream()
                // 全部来源也仅纳入当前问数授权内的独立单源请求。
                .filter(option -> source == null || option.getId().equals(source))
                .map(option -> Ids.parse(option.getId()))
                .toList();
        var windows = period.windowsWithTotal();
        var requests = scopedIds.isEmpty()
                ? List.<DashboardOverviewMapper.Usage>of()
                : mapper.usage(scopedIds, windows, period.asOf());

        // 保留同一日期和时区，把相同截止点传给原有模块接口。
        var snapshot = new OverviewQuery();
        snapshot.setFromDate(period.fromDate());
        snapshot.setToDate(period.toDate());
        snapshot.setTimeZone(period.zone().getId());
        snapshot.setSourceId(source);
        snapshot.setAsOf(asOf);

        var collections = canReadSources ? sources.overview(snapshot) : null;
        var confirmed = canReadGovernance ? governance.overview(snapshot) : null;
        var documents = canReadKnowledge ? knowledge.overview(snapshot) : null;
        var issues = canReadFeedback && !scopedIds.isEmpty() ? feedback.overview(snapshot) : null;

        var panels = new HashSet<String>();
        if (!requests.isEmpty()) {
            // 有范围但没有请求时，仍显示真实的零次。
            panels.add("query");
        }
        if (collections != null) {
            // 各模块独立核验权限，仅发布已返回的图表。
            panels.add("collections");
        }
        if (confirmed != null) {
            panels.add("governance");
        }
        if (documents != null) {
            panels.add("knowledge");
        }
        if (issues != null) {
            panels.add("feedback");
        }

        int last = windows.size() - 1;
        var points = windows.stream()
                .map(window -> {
                    // 各模块使用同一个窗口顺序，最后一个窗口是各自的全期口径。
                    int index = window.index();
                    var usage = requests.isEmpty() ? null : requests.get(index);
                    var collection = collections == null
                            ? null
                            : index == last
                                    ? collections.getTotal()
                                    : collections.getPoints().get(index);
                    var confirmation = confirmed == null
                            ? null
                            : index == last
                                    ? confirmed.getTotal()
                                    : confirmed.getPoints().get(index);
                    var document = documents == null
                            ? null
                            : index == last
                                    ? documents.getTotal()
                                    : documents.getPoints().get(index);
                    var issue = issues == null
                            ? null
                            : index == last
                                    ? issues.getTotal()
                                    : issues.getPoints().get(index);
                    return new Point(
                            window.fromDate(),
                            window.toDate(),
                            usage == null ? null : usage.requests(),
                            usage == null ? null : usage.delivered(),
                            collection == null ? null : collection.getSucceeded(),
                            collection == null ? null : collection.getFailed(),
                            confirmation == null ? null : confirmation.getTableConfirmed(),
                            confirmation == null ? null : confirmation.getFieldConfirmed(),
                            document == null ? null : document.getAvailable(),
                            issue == null ? null : issue.getAdded(),
                            issue == null ? null : issue.getResolved());
                })
                .toList();
        return new DashboardOverviewVO(
                options, asOf, period.grain(), points.subList(0, last), points.get(last), panels, authorized.size());
    }
}
