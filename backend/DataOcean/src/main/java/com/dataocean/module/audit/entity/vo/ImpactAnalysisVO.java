package com.dataocean.module.audit.entity.vo;

import lombok.Data;
import java.util.List;

/**
 * 变更影响分析视图对象
 */
@Data
public class ImpactAnalysisVO {
    /** 依赖该字段的查询数量 */
    private Long dependentQueryCount;
    /** 最近引用的查询任务ID列表 */
    private List<Long> recentQueryTaskIds;
    /** 表名 */
    private String tableName;
    /** 字段名 */
    private String columnName;
}
