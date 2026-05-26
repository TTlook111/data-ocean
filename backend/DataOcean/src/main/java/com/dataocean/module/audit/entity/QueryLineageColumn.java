package com.dataocean.module.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 查询血缘-字段级关系实体类
 * <p>
 * 记录 SQL 中引用的字段、表达式和别名。
 * </p>
 */
@Data
@TableName("query_lineage_column")
public class QueryLineageColumn {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long queryTaskId;
    private String sourceTable;
    private String sourceColumn;
    private String expression;
    private String aliasName;
    private LocalDateTime createdAt;
}
