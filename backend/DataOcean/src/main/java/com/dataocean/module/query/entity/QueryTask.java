package com.dataocean.module.query.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * NL2SQL 查询任务实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("query_task")
public class QueryTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务唯一标识（UUID） */
    private String taskId;

    /** 提交用户 ID */
    private Long userId;

    /** 数据源 ID */
    private Long datasourceId;

    /** 用户自然语言问题 */
    private String question;

    /** 改写后的结构化查询 */
    private String rewrittenQuery;

    /** 任务状态 */
    private String status;

    /** 最终生成的 SQL */
    private String resultSql;

    /** SQL 口径说明 */
    private String sqlExplanation;

    /** 查询结果数据 JSON */
    private String resultData;

    /** 结果列元信息 JSON */
    private String resultColumns;

    /** ECharts 图表配置 JSON */
    private String chartConfig;

    /** 使用的表列表 JSON */
    private String usedTables;

    /** 使用的字段列表 JSON */
    private String usedColumns;

    /** 错误信息 */
    private String errorMessage;

    /** 重试次数 */
    private Integer retryCount;

    /** 总耗时（毫秒） */
    private Integer totalTimeMs;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 完成时间 */
    private LocalDateTime completedAt;
}
