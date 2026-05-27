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

    Handles: direct columns, aliased columns, expressions containing sensitive columns,
    derived tables (subqueries in FROM), and CTEs.
    """
    if not mask_columns:
        return {}

    if not isinstance(tree, exp.Select):
        return {}

    mask_by_table = _normalize_column_map(mask_columns)

    # Build a "sensitive output names" set that includes:
    # 1. Physical sensitive columns from real tables
    # 2. Aliases from subqueries/CTEs that expose sensitive columns
    sensitive_aliases = _collect_subquery_sensitive_aliases(tree, mask_by_table, mask_strategies)

    alias_map = _build_top_level_alias_map(tree)
    scope_tables = set(alias_map.values())

    masked: dict[str, str] = {}

    for expr in tree.expressions:
        output_name = expr.alias if isinstance(expr, exp.Alias) else None
        inner = expr.this if isinstance(expr, exp.Alias) else expr

        # Determine the output key (what the result-set column will be named)
        if output_name:
            out_key = output_name
        elif isinstance(inner, exp.Column):
            out_key = inner.name.lower()
        else:
            # No alias on a complex expression: MySQL uses the expression SQL text as column name
            out_key = expr.sql(dialect="mysql") if expr else None

        if not out_key:
            continue

        # Check if this expression references any sensitive column
        found_strategy = _find_sensitive_in_expr(
            inner, mask_by_table, mask_strategies, alias_map, scope_tables, sensitive_aliases
        )
        if found_strategy:
            masked[out_key] = found_strategy

    return masked


def _collect_subquery_sensitive_aliases(
    tree: exp.Select,
    mask_by_table: dict[str, set[str]],
    mask_strategies: dict[str, str],
) -> dict[str, str]:
    """Analyze subqueries in FROM and CTEs to find output aliases that expose sensitive data.

    Returns {qualified_key: strategy} where keys include both:
    - "source_alias.col_name" (for qualified references like u.contact)
    - "col_name" (for unqualified references, only if unambiguous)
    """
    sensitive: dict[str, str] = {}
    # Track which unqualified names are ambiguous (appear in multiple sources)
    unqualified_seen: dict[str, str] = {}
    ambiguous: set[str] = set()

    def _register(source_alias: str, sub_masked: dict[str, str]) -> None:
        for out_name, strategy in sub_masked.items():
            key_lower = out_name.lower()
            # Always store qualified version
            if source_alias:
                sensitive[f"{source_alias}.{key_lower}"] = strategy
            # Track unqualified for ambiguity detection
            if key_lower in unqualified_seen and unqualified_seen[key_lower] != strategy:
                ambiguous.add(key_lower)
            else:
                unqualified_seen[key_lower] = strategy

    # Check CTEs
    with_clause = tree.find(exp.With)
    if with_clause:
        for cte in with_clause.find_all(exp.CTE):
            cte_alias = cte.alias.lower() if cte.alias else ""
            cte_select = cte.find(exp.Select)
            if cte_select and cte_alias:
                sub_masked = _mark_sensitive_columns(cte_select, dict(_denormalize(mask_by_table)), mask_strategies)
                _register(cte_alias, sub_masked)

    # Check derived tables (subqueries in FROM/JOIN)
    from_clause = tree.find(exp.From)
    sources = [from_clause] if from_clause else []
    sources.extend(tree.find_all(exp.Join))

    for source in sources:
        for subquery in source.find_all(exp.Subquery):
            sub_alias = subquery.alias.lower() if subquery.alias else ""
            sub_select = subquery.find(exp.Select)
            if sub_select:
                sub_masked = _mark_sensitive_columns(sub_select, dict(_denormalize(mask_by_table)), mask_strategies)
                _register(sub_alias, sub_masked)

    # Add unambiguous unqualified keys
    for key, strategy in unqualified_seen.items():
        if key not in ambiguous:
            sensitive[key] = strategy

    return sensitive


def _denormalize(mask_by_table: dict[str, set[str]]) -> list[tuple[str, list[str]]]:
    """Convert normalized {table: set(cols)} back to {table: [cols]} for recursive calls."""
    return [(table, list(cols)) for table, cols in mask_by_table.items()]


def _find_sensitive_in_expr(
    expr: exp.Expression,
    mask_by_table: dict[str, set[str]],
    mask_strategies: dict[str, str],
    alias_map: dict[str, str],
    scope_tables: set[str],
    sensitive_aliases: dict[str, str],
) -> str:
    """Recursively check if an expression references any sensitive column.

    Also checks against sensitive_aliases (from subqueries/CTEs).
    Returns the mask strategy if found, empty string otherwise.
    """
    if isinstance(expr, exp.Column):
        col_name = expr.name.lower()
        qualifier = expr.table.lower() if expr.table else ""

        # Check against subquery/CTE sensitive aliases (qualified first, then unqualified)
        if qualifier:
            qualified_key = f"{qualifier}.{col_name}"
            if qualified_key in sensitive_aliases:
                return sensitive_aliases[qualified_key]
        if col_name in sensitive_aliases:
            return sensitive_aliases[col_name]

        real_table = None
        if qualifier:
            real_table = alias_map.get(qualifier, qualifier)
        elif len(scope_tables) == 1:
            real_table = next(iter(scope_tables))

        if real_table and col_name in mask_by_table.get(real_table, set()):
            strategy = mask_strategies.get(f"{real_table}.{col_name}", "")
            return strategy if strategy else "UNKNOWN"

        if not real_table:
            for table_name, cols in mask_by_table.items():
                if col_name in cols:
                    strategy = mask_strategies.get(f"{table_name}.{col_name}", "")
                    return strategy if strategy else "UNKNOWN"

        return ""

    # Recurse into child expressions
    for child in expr.iter_expressions():
        result = _find_sensitive_in_expr(child, mask_by_table, mask_strategies, alias_map, scope_tables, sensitive_aliases)
        if result:
            return result

    return ""


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
