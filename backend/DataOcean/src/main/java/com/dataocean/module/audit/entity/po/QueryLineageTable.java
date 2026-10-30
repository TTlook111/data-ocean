package com.dataocean.module.audit.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 查询血缘-表级关系实体类
 * <p>
 * 记录查询使用的表；新 S1 记录 USAGE，不推断物理表之间的数据流。
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
