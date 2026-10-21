package com.dataocean.module.governance.entity.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class QualityIssueSummaryVO {
    private Long totalUnresolved;
    private Map<String, Long> statusCounts;
    private List<QualityIssueVO> recentIssues;
}
