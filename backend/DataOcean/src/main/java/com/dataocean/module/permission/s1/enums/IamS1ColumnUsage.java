package com.dataocean.module.permission.s1.enums;

/** SQL 中引用字段的位置；B3 会据此执行 AST 约束。 */
public enum IamS1ColumnUsage {
    /** 出现在 SELECT 投影列表中：决定字段值能否被暴露。 */
    PROJECTION,
    /** 出现在 WHERE 中：字段仅用于收窄结果。 */
    FILTER,
    /** 出现在 JOIN ON 中。 */
    JOIN,
    /** 出现在 ORDER BY 中。 */
    ORDER,
    /** 出现在 GROUP BY 中。 */
    GROUP,
    /** 出现在 HAVING 中。 */
    HAVING,
    /** 被函数包裹（聚合、CASE、窗口等）。 */
    FUNCTION,
    /** 出现在子查询中。 */
    SUBQUERY
}
