package com.dataocean.module.query.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 查询任务结果 VO
 */
@Data
@Builder
public class QueryTaskVO {

    private String taskId;
    private String status;
    /** 当前执行节点（实时进度） */
    private String progressNode;
    /** 当前进度提示文案（如“正在生成 SQL”） */
    private String progressMessage;
    private String question;
    private String rewrittenQuery;
    private String sql;
    private String sqlExplanation;
    private List<Map<String, Object>> data;
    private List<Map<String, String>> columns;
    private Integer rowCount;
    private Map<String, Object> chartConfig;
    private List<String> usedTables;
    private List<String> usedColumns;
    private List<Map<String, Object>> promptVersions;
    private String errorMessage;
    private Integer retryCount;
    private Integer totalTimeMs;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    /** 是否允许导出（前端据此控制导出按钮显示） */
    private Boolean canExport;
}
