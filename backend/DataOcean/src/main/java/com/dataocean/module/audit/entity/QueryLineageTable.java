package com.dataocean.module.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 查询血缘-表级关系实体类
 * <p>
 * 记录 SQL 中引用的表及其关系类型（FROM/JOIN/SUBQUERY）。
 * </p>
 */
@Data
@TableName("query_lineage_table")
public class QueryLineageTable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long queryTaskId;
    private String sourceTable;
    private String targetName;
    private String relationType;
    private LocalDateTime createdAt;
}
