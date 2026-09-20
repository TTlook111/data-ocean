package com.dataocean.module.dashboard.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.dashboard.entity.vo.DashboardStatsVO;
import com.dataocean.module.dashboard.service.DashboardService;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.governance.entity.MetadataQualityIssue;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.dataocean.module.user.mapper.UserMapper;
import com.dataocean.module.versioning.entity.SnapshotAuditLog;
import com.dataocean.module.versioning.mapper.SnapshotAuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理端首页看板服务实现。
 * <p>
 * 从用户、数据源、元数据快照、质量问题和快照审计日志中汇总概览指标。
 * 每个卡片按其所属域的 S1 功能码与负责源裁剪，不再是无差别的全站统计：
 * 工作台本身要求全局功能 `admin:workbench:view`，各卡片再按对应功能码的负责源过滤。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final UserMapper userMapper;
    private final DatasourceMapper datasourceMapper;
    private final MetadataSnapshotMapper snapshotMapper;
    private final MetadataQualityIssueMapper qualityIssueMapper;
    private final SnapshotAuditLogMapper auditLogMapper;
    private final IamS1AdminGuard adminGuard;
    private final IamS1AuthorizationResolver authorizationResolver;
    private final IamS1CapabilityService capabilityService;

    /**
     * {@inheritDoc}
     */
    @Override
    public DashboardStatsVO getStats(Long userId) {
        adminGuard.requireGlobalFunction(userId, "admin:workbench:view");

        DashboardStatsVO stats = new DashboardStatsVO();

        // 用户总数属于组织域，与数据源负责范围无关：按全局功能码决定是否统计，
        // 没有该功能时卡片归零，而不是返回全站用户数。
        stats.setTotalUsers(authorizationResolver.hasGlobalFunction(userId, "organization:user:view")
                ? userMapper.selectCount(null)
                : 0L);

        List<Long> datasourceScope = datasourceScopeOf(userId, "datasource:view");
        List<Long> metadataScope = datasourceScopeOf(userId, "metadata:view");
        List<Long> governanceScope = datasourceScopeOf(userId, "governance:issue:view");
        List<Long> releaseScope = datasourceScopeOf(userId, "metadata:release:view");

        stats.setTotalDatasources(datasourceScope.isEmpty() ? 0L : datasourceMapper.selectCount(
                new LambdaQueryWrapper<Datasource>()
                        .eq(Datasource::getDeleted, 0)
                        .in(Datasource::getId, datasourceScope)));
        stats.setActiveDatasources(datasourceScope.isEmpty() ? 0L : datasourceMapper.selectCount(
                new LambdaQueryWrapper<Datasource>()
                        .eq(Datasource::getDeleted, 0)
                        .eq(Datasource::getStatus, Datasource.STATUS_ENABLED)
                        .in(Datasource::getId, datasourceScope)));

        stats.setTotalSnapshots(metadataScope.isEmpty() ? 0L : snapshotMapper.selectCount(
                new LambdaQueryWrapper<MetadataSnapshot>()
                        .in(MetadataSnapshot::getDatasourceId, metadataScope)));
        stats.setPublishedSnapshots(metadataScope.isEmpty() ? 0L : snapshotMapper.selectCount(
                new LambdaQueryWrapper<MetadataSnapshot>()
                        .eq(MetadataSnapshot::getStatus, MetadataSnapshot.STATUS_PUBLISHED)
                        .in(MetadataSnapshot::getDatasourceId, metadataScope)));

        // 表和字段统计（取发布快照的数据，只覆盖负责源）
        List<MetadataSnapshot> publishedList = metadataScope.isEmpty()
                ? List.of()
                : snapshotMapper.selectList(new LambdaQueryWrapper<MetadataSnapshot>()
                        .eq(MetadataSnapshot::getStatus, MetadataSnapshot.STATUS_PUBLISHED)
                        .in(MetadataSnapshot::getDatasourceId, metadataScope));
        long totalTables = 0;
        long totalColumns = 0;
        double qualitySum = 0;
        int qualityCount = 0;
        for (MetadataSnapshot s : publishedList) {
            totalTables += (s.getTableCount() != null ? s.getTableCount() : 0);
            totalColumns += (s.getColumnCount() != null ? s.getColumnCount() : 0);
            if (s.getQualityScore() != null) {
                qualitySum += s.getQualityScore().doubleValue();
                qualityCount++;
            }
        }
        stats.setTotalTables(totalTables);
        stats.setTotalColumns(totalColumns);
        stats.setAvgQualityScore(qualityCount > 0 ? Math.round(qualitySum / qualityCount * 10.0) / 10.0 : null);

        stats.setOpenIssues(countIssues(governanceScope, List.of("OPEN", "CONFIRMED")));
        stats.setResolvedIssues(countIssues(governanceScope, List.of("RESOLVED")));

        List<SnapshotAuditLog> recentLogs = releaseScope.isEmpty()
                ? List.of()
                : auditLogMapper.selectList(new LambdaQueryWrapper<SnapshotAuditLog>()
                        .in(SnapshotAuditLog::getDatasourceId, releaseScope)
                        .orderByDesc(SnapshotAuditLog::getCreatedAt)
                        .last("LIMIT 5"));
        List<DashboardStatsVO.RecentActivity> activities = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm");
        for (SnapshotAuditLog log : recentLogs) {
            DashboardStatsVO.RecentActivity a = new DashboardStatsVO.RecentActivity();
            a.setType(log.getAction());
            a.setDescription(buildDescription(log));
            a.setTime(log.getCreatedAt() != null ? log.getCreatedAt().format(fmt) : "");
            activities.add(a);
        }
        stats.setRecentActivities(activities);

        return stats;
    }

    /** 在某功能上可操作的数据源 ID；系统管理员返回全部启用源。 */
    private List<Long> datasourceScopeOf(Long userId, String functionCode) {
        return capabilityService.responsibleDatasourcesWithFunction(userId, functionCode)
                .stream()
                .map(IamS1DatasourceRefVO::id)
                .toList();
    }

    private long countIssues(List<Long> scope, List<String> statuses) {
        if (scope.isEmpty()) {
            return 0L;
        }
        return qualityIssueMapper.selectCount(new LambdaQueryWrapper<MetadataQualityIssue>()
                .in(MetadataQualityIssue::getStatus, statuses)
                .in(MetadataQualityIssue::getDatasourceId, scope));
    }

    private String buildDescription(SnapshotAuditLog log) {
        String action = log.getAction();
        if ("PUBLISH".equals(action)) return "快照已发布";
        if ("EXPIRE".equals(action)) return "快照已过期";
        if ("REVOKE".equals(action)) return "快照已撤回";
        if ("STATUS_TRANSITION".equals(action)) {
            return (log.getOldStatus() != null ? log.getOldStatus() : "") + " → " +
                    (log.getNewStatus() != null ? log.getNewStatus() : "");
        }
        return action;
    }
}
