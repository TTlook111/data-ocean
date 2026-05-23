package com.dataocean.module.dashboard.entity.vo;

import lombok.Data;

import java.util.List;

/**
 * 管理端首页统计视图对象。
 * <p>
 * 用于承载平台规模、快照发布、质量问题和近期操作记录等看板指标。
 * </p>
 */
@Data
public class DashboardStatsVO {

    private long totalUsers;
    private long totalDatasources;
    private long activeDatasources;
    private long totalSnapshots;
    private long publishedSnapshots;
    private long totalTables;
    private long totalColumns;
    private long openIssues;
    private long resolvedIssues;
    private Double avgQualityScore;
    private List<RecentActivity> recentActivities;

    /**
     * 首页近期活动视图对象。
     */
    @Data
    public static class RecentActivity {
        private String type;
        private String description;
        private String time;
    }
}
