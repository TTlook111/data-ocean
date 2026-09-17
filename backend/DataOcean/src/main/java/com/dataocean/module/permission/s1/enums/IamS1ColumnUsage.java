package com.dataocean.module.permission.s1.enums;

/** SQL 中引用字段的位置；B3 会据此执行 AST 约束。 */
public enum IamS1ColumnUsage {
    PROJECTION,
    FILTER,
    JOIN,
    ORDER,
    GROUP,
    FUNCTION,
    SUBQUERY
}
