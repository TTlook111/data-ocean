package com.dataocean.module.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 查询配额策略实体类
 * <p>
 * 按用户/部门/数据源维度限制每日查询次数和月度成本。
 * </p>
 */
@Data
@TableName("quota_policy")
public class QuotaPolicy {
    public static final String TYPE_USER = "USER";
    public static final String TYPE_DEPARTMENT = "DEPARTMENT";
    public static final String TYPE_DATASOURCE = "DATASOURCE";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String subjectType;
    private Long subjectId;
    private Integer dailyQueryLimit;
    private BigDecimal monthlyCostLimit;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
