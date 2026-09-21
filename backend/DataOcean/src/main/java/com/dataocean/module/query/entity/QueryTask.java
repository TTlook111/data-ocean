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

    /** IAM-SIMPLE-1 查询协议；旧任务保持为空。 */
    private String iamProtocolVersion;

    /** S1 活动元数据快照与权限修订证据。 */
    private Long activeMetadataSnapshotId;
    private Long permissionRevision;

    /** 不含记录参数原值的 S1 执行快照。 */
    private String iamExecutionSnapshot;
    /** 本次查询实际引用资源请求的安全 JSON。 */
    private String iamResourceRequest;
    /** Python 返回的来源追踪安全摘要。 */
    private String iamSourceTrace;
    /** query/viewSql/export 能力摘要。 */
    private String iamCapabilities;
    /** 最终保护状态，例如 FINAL_MASKED / REJECTED_ON_RECHECK。 */
    private String iamFinalProtectionStatus;

    /** 关联的会话 ID */
    private Long conversationId;

    /** 用户自然语言问题 */
    private String question;

    /** 改写后的结构化查询 */
    private String rewrittenQuery;

    /** 任务状态 */
    private String status;

    /** 当前执行节点（实时进度，来自 Python SSE progress 事件） */
    private String progressNode;

    /** 当前进度提示文案（如“正在生成 SQL”） */
    private String progressMessage;

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

    /** 列级派生关系 JSON（Phase 1：sqlglot AST 提取的列→列血缘） */
    private String columnDerivations;

    /** Python AST 标记的需脱敏字段列表 JSON（格式: ["table.column", ...]） */
    private String maskedFields;

    /** Python 推荐的后续问题 JSON */
    private String suggestedQuestions;

    /** 本次 Agent 调用使用的 Prompt 模板版本 JSON */
    private String promptVersions;

    /** 是否使用了降级方案 */
    private Boolean degraded;

    /** 降级提示信息 */
    private String degradeNotice;

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
