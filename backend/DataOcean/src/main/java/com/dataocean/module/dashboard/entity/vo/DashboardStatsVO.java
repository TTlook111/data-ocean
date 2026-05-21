package com.dataocean.module.dashboard.entity.vo;

import lombok.Data;

import java.util.List;

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

    @Data
    public static class RecentActivity {
        private String type;
        private String description;
        private String time;
    }
}
