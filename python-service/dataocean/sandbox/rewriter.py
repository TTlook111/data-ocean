"""SQL AST rewriter for row filters, column access checks, masking hints, and LIMIT."""

from __future__ import annotations

import logging
from dataclasses import dataclass, field

import sqlglot
from sqlglot import exp
from sqlglot.optimizer.scope import traverse_scope

from .config import sandbox_config

logger = logging.getLogger(__name__)


@dataclass
class RewriteResult:
    """Result of rewriting a validated SQL statement."""

    success: bool
    rewritten_sql: str = ""
    masked_fields: dict[str, str] = field(default_factory=dict)
    denied_reason: str = ""


def rewrite(
    sql: str,
    row_filters: dict[str, list[str]] | None = None,
    denied_columns: dict[str, list[str]] | None = None,
    mask_columns: dict[str, list[str]] | None = None,
    mask_strategies: dict[str, str] | None = None,
) -> RewriteResult:
    """Rewrite validated SQL and apply hard permission checks.

    Args:
        mask_strategies: optional mapping of "table.column" -> strategy name (e.g. "PHONE")
    """
    try:
        tree = sqlglot.parse_one(sql, dialect="mysql")
    except sqlglot.errors.ParseError as e:
        return RewriteResult(success=False, denied_reason=f"SQL 解析失败：{e}")

    if denied_columns:
        denied = _check_column_access(tree, denied_columns)
        if denied:
            return RewriteResult(success=False, denied_reason=denied)

    if row_filters:
        try:
            tree = _inject_row_filters(tree, row_filters)
        except ValueError as e:
            return RewriteResult(success=False, denied_reason=str(e))

    tree = _inject_limit(tree)
    masked_fields = _mark_sensitive_columns(tree, mask_columns or {}, mask_strategies or {})
    rewritten_sql = tree.sql(dialect="mysql")

    try:
        sqlglot.parse_one(rewritten_sql, dialect="mysql")
    except sqlglot.errors.ParseError as e:
        logger.error("Rewritten SQL failed parse validation sql=%s error=%s", rewritten_sql[:100], e)
        return RewriteResult(success=False, denied_reason=f"改写后 SQL 语法异常：{e}")

    return RewriteResult(success=True, rewritten_sql=rewritten_sql, masked_fields=masked_fields)


def _inject_row_filters(tree: exp.Expression, row_filters: dict[str, list[str]]) -> exp.Expression:
    """Inject row filters only into scopes that actually reference the target table."""
    normalized_filters = {table_name.lower(): conditions for table_name, conditions in row_filters.items()}

    for scope in traverse_scope(tree):
        table_refs = _scope_table_refs(scope)
        if not table_refs or not isinstance(scope.expression, exp.Select):
            continue

        for alias, table_name in table_refs:
            conditions = normalized_filters.get(table_name)
            if not conditions:
                continue

            for condition_str in conditions:
                try:
                    condition_expr = _parse_condition(condition_str)
                    condition_expr = _qualify_condition(condition_expr, table_name, alias)
                    scope.expression.where(condition_expr, copy=False)
                except Exception as exc:
                    logger.error(
                        "Unable to parse row filter table=%s condition=%s error=%s",
                        table_name,
                        condition_str,
                        exc,
                    )
                    raise ValueError(f"行级过滤条件语法错误：{condition_str}") from exc

    return tree


def _parse_condition(condition_str: str) -> exp.Expression:
    """Parse a row-filter condition expression."""
    try:
        return sqlglot.parse_one(condition_str, dialect="mysql", into=exp.Condition)
    except Exception:
        return sqlglot.condition(condition_str, dialect="mysql")


def _qualify_condition(condition_expr: exp.Expression, table_name: str, alias: str) -> exp.Expression:
    """Bind row-filter columns to the concrete alias used by the query scope."""
    table_name = table_name.lower()
    alias = alias.lower()

    for col_node in condition_expr.find_all(exp.Column):
        qualifier = col_node.table.lower() if col_node.table else ""
        if not qualifier:
            col_node.set("table", exp.to_identifier(alias))
        elif qualifier == table_name and alias != table_name:
            col_node.set("table", exp.to_identifier(alias))
        elif qualifier not in {table_name, alias}:
            raise ValueError(f"行级过滤条件引用了未知表别名：{qualifier}")

    return condition_expr


def _check_column_access(tree: exp.Expression, denied_columns: dict[str, list[str]]) -> str:
    """Return a denial reason if SQL references any denied column."""
    denied_by_table = _normalize_column_map(denied_columns)

    for scope in traverse_scope(tree):
        alias_map = _scope_alias_map(scope)
        scope_tables = set(alias_map.values())

        for col_node in scope.columns:
            col_name = col_node.name.lower()
            qualifier = col_node.table.lower() if col_node.table else ""

            if qualifier:
                real_table = alias_map.get(qualifier, qualifier)
                if col_name in denied_by_table.get(real_table, set()):
                    return f"无权访问字段：{real_table}.{col_name}"
                continue

            if len(scope_tables) == 1:
                real_table = next(iter(scope_tables))
                if col_name in denied_by_table.get(real_table, set()):
                    return f"无权访问字段：{real_table}.{col_name}"
                continue

            for table_name, cols in denied_by_table.items():
                if col_name in cols:
                    return f"无权访问字段：{table_name}.{col_name}"

    return ""


def _mark_sensitive_columns(
    tree: exp.Expression,
    mask_columns: dict[str, list[str]],
    mask_strategies: dict[str, str],
) -> dict[str, str]:
    """Return mapping of output column name → mask strategy for the Java gateway.

    Returns {output_name: strategy} where output_name is the result-set column name
    (alias if present, otherwise physical column name).
    """
    if not mask_columns:
        return {}

    mask_by_table = _normalize_column_map(mask_columns)
    masked: dict[str, str] = {}

    if not isinstance(tree, exp.Select):
        return {}

    alias_map = _build_top_level_alias_map(tree)
    scope_tables = set(alias_map.values())

    for expr in tree.expressions:
        output_name = expr.alias if isinstance(expr, exp.Alias) else None
        inner = expr.this if isinstance(expr, exp.Alias) else expr

        if not isinstance(inner, exp.Column):
            continue

        col_name = inner.name.lower()
        qualifier = inner.table.lower() if inner.table else ""

        real_table = None
        if qualifier:
            real_table = alias_map.get(qualifier, qualifier)
        elif len(scope_tables) == 1:
            real_table = next(iter(scope_tables))

        matched = False
        if real_table and col_name in mask_by_table.get(real_table, set()):
            matched = True
        elif not real_table:
            for table_name, cols in mask_by_table.items():
                if col_name in cols:
                    real_table = table_name
                    matched = True
                    break

        if matched:
            out_key = output_name if output_name else col_name
            # 查找策略：先精确匹配 "table.column"，再 fallback 到列名
            strategy = mask_strategies.get(f"{real_table}.{col_name}", "")
            if not strategy:
                strategy = mask_strategies.get(col_name, "UNKNOWN")
            masked[out_key] = strategy

    return masked


def _build_top_level_alias_map(tree: exp.Select) -> dict[str, str]:
    """Build alias→table mapping for the top-level SELECT's FROM clause."""
    aliases: dict[str, str] = {}
    from_clause = tree.find(exp.From)
    if from_clause:
        for table_expr in from_clause.find_all(exp.Table):
            table_name = table_expr.name.lower()
            alias = table_expr.alias.lower() if table_expr.alias else table_name
            aliases[alias] = table_name
            aliases[table_name] = table_name
    # Also check JOINs
    for join in tree.find_all(exp.Join):
        table_expr = join.find(exp.Table)
        if table_expr:
            table_name = table_expr.name.lower()
            alias = table_expr.alias.lower() if table_expr.alias else table_name
            aliases[alias] = table_name
            aliases[table_name] = table_name
    return aliases


def _inject_limit(tree: exp.Expression) -> exp.Expression:
    """Inject or cap only the outer SELECT LIMIT."""
    if not isinstance(tree, exp.Select):
        return tree

    max_rows = sandbox_config.max_result_rows
    limit_node = tree.args.get("limit")

    if limit_node is None:
        return tree.limit(max_rows, copy=False)

    try:
        limit_expr = limit_node.expression
        if isinstance(limit_expr, exp.Literal):
            current_value = int(limit_expr.this)
            if current_value > max_rows:
                limit_node.set("expression", exp.Literal.number(max_rows))
    except (ValueError, TypeError):
        pass

    return tree


def _normalize_column_map(column_map: dict[str, list[str]]) -> dict[str, set[str]]:
    """Normalize permission maps to lowercase table -> lowercase column set."""
    normalized: dict[str, set[str]] = {}
    for table, cols in column_map.items():
        normalized.setdefault(table.lower(), set()).update(col.lower() for col in cols)
    return normalized


def _scope_table_refs(scope) -> list[tuple[str, str]]:
    """Return (alias_or_name, real_table_name) pairs for concrete tables in one scope."""
    refs: list[tuple[str, str]] = []
    for alias, (_, source) in scope.selected_sources.items():
        if isinstance(source, exp.Table):
            table_name = source.name.lower()
            if table_name:
                refs.append((alias.lower(), table_name))
    return refs


def _scope_alias_map(scope) -> dict[str, str]:
    """Map aliases and table names in a scope to real physical table names."""
    aliases: dict[str, str] = {}
    for alias, table_name in _scope_table_refs(scope):
        aliases[alias] = table_name
        aliases[table_name] = table_name
    return aliases
