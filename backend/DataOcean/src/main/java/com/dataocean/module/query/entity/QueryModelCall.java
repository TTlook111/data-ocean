package com.dataocean.module.query.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Durable per-task LLM budget reservation; prompt bodies and credentials are not stored. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("query_model_call")
public class QueryModelCall {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskId;
    private String callId;
    private String nodeName;
    private String modelName;
    private String status;
    private Integer inputTokens;
    private Integer outputTokens;
    private BigDecimal reservedCostCny;
    private BigDecimal actualCostCny;
    private Integer usageEstimated;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
