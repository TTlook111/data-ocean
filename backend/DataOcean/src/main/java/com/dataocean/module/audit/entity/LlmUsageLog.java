package com.dataocean.module.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * LLM 调用日志实体类
 * <p>
 * 记录每次 LLM API 调用的 Token 消耗和费用。
 * </p>
 */
@Data
@TableName("llm_usage_log")
public class LlmUsageLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long queryTaskId;
    private String provider;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private BigDecimal costAmount;
    private LocalDateTime createdAt;
}
