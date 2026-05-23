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

    /**
     * {@inheritDoc}
     */
    @Override
    public DashboardStatsVO getStats() {
        DashboardStatsVO stats = new DashboardStatsVO();

        stats.setTotalUsers(userMapper.selectCount(null));

        stats.setTotalDatasources(datasourceMapper.selectCount(
                new LambdaQueryWrapper<Datasource>().eq(Datasource::getDeleted, 0)));
        stats.setActiveDatasources(datasourceMapper.selectCount(
                new LambdaQueryWrapper<Datasource>()
                        .eq(Datasource::getDeleted, 0)
                        .eq(Datasource::getStatus, Datasource.STATUS_ENABLED)));

        stats.setTotalSnapshots(snapshotMapper.selectCount(null));
        stats.setPublishedSnapshots(snapshotMapper.selectCount(
                new LambdaQueryWrapper<MetadataSnapshot>()
                        .eq(MetadataSnapshot::getStatus, MetadataSnapshot.STATUS_PUBLISHED)));

        // 表和字段统计（取最新发布快照的数据）
        List<MetadataSnapshot> publishedList = snapshotMapper.selectList(
                new LambdaQueryWrapper<MetadataSnapshot>()
                        .eq(MetadataSnapshot::getStatus, MetadataSnapshot.STATUS_PUBLISHED));
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

        stats.setOpenIssues(qualityIssueMapper.selectCount(
                new LambdaQueryWrapper<MetadataQualityIssue>()
                        .in(MetadataQualityIssue::getStatus, "OPEN", "CONFIRMED")));
        stats.setResolvedIssues(qualityIssueMapper.selectCount(
                new LambdaQueryWrapper<MetadataQualityIssue>()
                        .eq(MetadataQualityIssue::getStatus, "RESOLVED")));

        List<SnapshotAuditLog> recentLogs = auditLogMapper.selectList(
                new LambdaQueryWrapper<SnapshotAuditLog>()
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
