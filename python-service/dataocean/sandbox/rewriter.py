"""SQL AST 改写器

在 AST 层强制注入行级过滤、列级访问控制和 LIMIT 限制。
改写后重新生成 SQL 并验证语法正确性。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field

import sqlglot
from sqlglot import exp

from .config import sandbox_config

logger = logging.getLogger(__name__)


@dataclass
class RewriteResult:
    """改写结果"""

    success: bool
    rewritten_sql: str = ""
    masked_fields: list[str] = field(default_factory=list)
    denied_reason: str = ""


def rewrite(
    sql: str,
    row_filters: dict[str, list[str]] | None = None,
    denied_columns: dict[str, list[str]] | None = None,
    mask_columns: dict[str, list[str]] | None = None,
) -> RewriteResult:
    """执行完整的 SQL 改写流程

    Args:
        sql: 已通过校验的 SQL
        row_filters: 行级过滤 {table_name: [condition_expr]}
        denied_columns: 禁止访问的列 {table_name: [column_name]}
        mask_columns: 需脱敏的列 {table_name: [column_name]}

    Returns:
        RewriteResult 包含改写后的 SQL 和脱敏字段列表
    """
    try:
        tree = sqlglot.parse_one(sql, dialect="mysql")
    except sqlglot.errors.ParseError as e:
        return RewriteResult(success=False, denied_reason=f"SQL 解析失败：{e}")

    # 列级访问检查（拒绝则直接返回）
    if denied_columns:
        denied = _check_column_access(tree, denied_columns)
        if denied:
            return RewriteResult(success=False, denied_reason=denied)

    # 行级过滤注入
    if row_filters:
        try:
            tree = _inject_row_filters(tree, row_filters)
        except ValueError as e:
            return RewriteResult(success=False, denied_reason=str(e))

    # 强制 LIMIT 注入
    tree = _inject_limit(tree)

    # 标记敏感字段
    masked_fields = _mark_sensitive_columns(tree, mask_columns or {})

    # 生成改写后的 SQL
    rewritten_sql = tree.sql(dialect="mysql")

    # 重新解析验证语法正确性
    try:
        sqlglot.parse_one(rewritten_sql, dialect="mysql")
    except sqlglot.errors.ParseError as e:
        logger.error("改写后 SQL 语法验证失败 sql=%s error=%s", rewritten_sql[:100], e)
        return RewriteResult(success=False, denied_reason=f"改写后 SQL 语法异常：{e}")

    return RewriteResult(success=True, rewritten_sql=rewritten_sql, masked_fields=masked_fields)


def _inject_row_filters(tree: exp.Select, row_filters: dict[str, list[str]]) -> exp.Select:
    """注入行级过滤条件到 WHERE 子句"""
    for table_name, conditions in row_filters.items():
        for condition_str in conditions:
            try:
                condition_expr = sqlglot.parse_one(condition_str, dialect="mysql", into=exp.Condition)
                tree = tree.where(condition_expr, copy=False)
            except Exception:
                # 尝试使用 condition() 解析
                try:
                    condition_expr = sqlglot.condition(condition_str, dialect="mysql")
                    tree = tree.where(condition_expr, copy=False)
                except Exception:
                    logger.error("行级过滤条件无法解析 table=%s condition=%s", table_name, condition_str)
                    raise ValueError(f"行级过滤条件语法错误：{condition_str}")
    return tree


def _check_column_access(tree: exp.Select, denied_columns: dict[str, list[str]]) -> str:
    """检查 SQL 中是否引用了禁止访问的列，返回拒绝原因或空字符串"""
    # 构建精确匹配集合（必须带表名前缀才匹配）
    denied_full_refs: set[str] = set()
    for table, cols in denied_columns.items():
        for col in cols:
            denied_full_refs.add(f"{table.lower()}.{col.lower()}")

    for col_node in tree.find_all(exp.Column):
        col_name = col_node.name.lower()
        table_name = col_node.table.lower() if col_node.table else ""

        if table_name:
            # 有表名前缀时精确匹配
            full_ref = f"{table_name}.{col_name}"
            if full_ref in denied_full_refs:
                return f"无权访问字段：{full_ref}"
        else:
            # 无表名前缀时，检查是否任何表的该列被禁止
            for denied_ref in denied_full_refs:
                if denied_ref.endswith(f".{col_name}"):
                    return f"无权访问字段：{col_name}（匹配 {denied_ref}）"

    return ""


def _mark_sensitive_columns(tree: exp.Select, mask_columns: dict[str, list[str]]) -> list[str]:
    """标记需要脱敏的字段列表"""
    if not mask_columns:
        return []

    mask_set: set[str] = set()
    for table, cols in mask_columns.items():
        for col in cols:
            mask_set.add(f"{table.lower()}.{col.lower()}")
            mask_set.add(col.lower())

    masked: list[str] = []
    for col_node in tree.find_all(exp.Column):
        col_name = col_node.name.lower()
        table_name = col_node.table.lower() if col_node.table else ""
        full_ref = f"{table_name}.{col_name}" if table_name else col_name

        if full_ref in mask_set or col_name in mask_set:
            masked.append(full_ref or col_name)

    return list(set(masked))


def _inject_limit(tree: exp.Select) -> exp.Select:
    """强制注入或修正 LIMIT"""
    max_rows = sandbox_config.max_result_rows
    limit_node = tree.find(exp.Limit)

    if limit_node is None:
        tree = tree.limit(max_rows, copy=False)
    else:
        try:
            limit_expr = limit_node.expression
            if isinstance(limit_expr, exp.Literal):
                current_value = int(limit_expr.this)
                if current_value > max_rows:
                    limit_node.set("expression", exp.Literal.number(max_rows))
        except (ValueError, TypeError):
            pass

    return tree
