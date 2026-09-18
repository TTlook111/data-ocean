"""IAM-SIMPLE-1 SQL AST validation and structured row-condition injection."""

from __future__ import annotations

from dataclasses import dataclass, field
import re
from typing import Any

import sqlglot
from sqlglot import exp
from sqlglot.optimizer.scope import traverse_scope

from .schema import S1ExecutionBinding, S1PermissionSnapshot
from .firewall import resource_index
from dataocean.sandbox.config import sandbox_config
from dataocean.sandbox.rules import depth_rule, function_rule, limit_rule


class S1SqlSecurityError(ValueError):
    """Fail-closed SQL security error."""


@dataclass
class S1SqlValidation:
    passed: bool
    sql: str = ""
    used_tables: list[str] = field(default_factory=list)
    used_columns: list[str] = field(default_factory=list)
    source_trace: list[dict[str, Any]] = field(default_factory=list)
    masked_fields: dict[str, str] = field(default_factory=dict)
    violations: list[str] = field(default_factory=list)


def _tables(tree: exp.Expression, snapshot: S1PermissionSnapshot) -> tuple[dict[str, str], set[str]]:
    ctes = {cte.alias_or_name.lower() for cte in tree.find_all(exp.CTE)}
    aliases: dict[str, str] = {}
    physical: set[str] = set()
    for node in tree.find_all(exp.Table):
        name = (node.name or "").lower()
        alias = (node.alias_or_name or name).lower()
        if not name or name in ctes:
            continue
        aliases[name] = name
        aliases[alias] = name
        physical.add(name)
    allowed = set(resource_index(snapshot))
    if not physical:
        raise S1SqlSecurityError("SQL 必须引用 S1 授权表")
    unknown = physical - allowed
    if unknown:
        raise S1SqlSecurityError(f"引用了未授权的表: {', '.join(sorted(unknown))}")
    return aliases, physical


def _select_tables(select: exp.Select, aliases: dict[str, str]) -> list[tuple[str, str]]:
    result: list[tuple[str, str]] = []
    for table in select.find_all(exp.Table):
        name = (table.name or "").lower()
        if not name or name not in aliases.values():
            continue
        alias = (table.alias_or_name or name).lower()
        if (name, alias) not in result:
            result.append((name, alias))
    return result


def _expand_stars(tree: exp.Expression, snapshot: S1PermissionSnapshot, aliases: dict[str, str]) -> None:
    allowed = resource_index(snapshot)
    for select in tree.find_all(exp.Select):
        tables = _select_tables(select, aliases)
        replacements: list[exp.Expression] = []
        changed = False
        for expression in list(select.expressions):
            table_name: str | None = None
            if isinstance(expression, exp.Star):
                if len(tables) != 1:
                    raise S1SqlSecurityError("无法安全展开 SELECT *")
                table_name = tables[0][0]
            elif isinstance(expression, exp.Column) and expression.name == "*":
                table_name = aliases.get((expression.table or "").lower())
                if table_name is None:
                    raise S1SqlSecurityError("无法安全展开 table.*")
            if table_name is None:
                replacements.append(expression)
                continue
            alias = next((alias for name, alias in tables if name == table_name), table_name)
            for column in allowed[table_name].values():
                replacements.append(exp.column(column["name"], table=alias))
            changed = True
        if changed:
            select.set("expressions", replacements)


def _resolve_column(column: exp.Column, aliases: dict[str, str], allowed: dict[str, dict[str, Any]]) -> tuple[str, str]:
    name = (column.name or "").lower()
    qualifier = (column.table or "").lower()
    if not name or name == "*":
        raise S1SqlSecurityError("SQL 中仍存在未展开的星号字段")
    if qualifier:
        table = aliases.get(qualifier)
        if table is None:
            raise S1SqlSecurityError(f"无法追踪字段来源: {qualifier}.{name}")
        if name not in allowed.get(table, {}):
            raise S1SqlSecurityError(f"引用了未授权字段: {table}.{name}")
        return table, name
    candidates = [table for table, columns in allowed.items() if name in columns]
    if len(candidates) != 1:
        raise S1SqlSecurityError(f"无法唯一追踪未限定字段: {name}")
    return candidates[0], name


def _is_direct_projection(column: exp.Column) -> bool:
    parent = column.parent
    if isinstance(parent, exp.Alias):
        return isinstance(parent.parent, exp.Select) and parent.this is column
    return isinstance(parent, exp.Select)


def _protection(column: exp.Column, table: str, name: str, allowed: dict[str, dict[str, Any]]) -> None:
    meta = allowed[table][name]
    level = meta.get("protectionLevel")
    if level == "HIDDEN":
        raise S1SqlSecurityError(f"隐藏字段不得引用: {table}.{name}")
    if level == "MASKED" and not _is_direct_projection(column):
        raise S1SqlSecurityError(f"脱敏字段只能直接投影: {table}.{name}")


def _trace(tree: exp.Expression, aliases: dict[str, str], allowed: dict[str, dict[str, Any]]) -> tuple[list[str], list[str], list[dict[str, Any]], dict[str, str]]:
    used_tables: set[str] = set()
    used_columns: set[str] = set()
    trace: list[dict[str, Any]] = []
    masked: dict[str, str] = {}
    for column in tree.find_all(exp.Column):
        table, name = _resolve_column(column, aliases, allowed)
        _protection(column, table, name, allowed)
        used_tables.add(table)
        used_columns.add(f"{table}.{name}")
    for select in tree.find_all(exp.Select):
        for output in select.expressions:
            output_name = output.alias_or_name or output.sql(dialect="mysql")
            sources: list[str] = []
            for column in output.find_all(exp.Column):
                table, name = _resolve_column(column, aliases, allowed)
                sources.append(f"{table}.{name}")
                meta = allowed[table][name]
                if meta.get("protectionLevel") == "MASKED":
                    masked[output_name] = str(meta.get("maskPolicy") or "")
            trace.append({"outputColumn": output_name, "sources": sorted(set(sources)), "expression": output.sql(dialect="mysql")})
    return sorted(used_tables), sorted(used_columns), trace, masked


def validate_sql(sql: str, snapshot: S1PermissionSnapshot) -> S1SqlValidation:
    try:
        statements = sqlglot.parse(sql, dialect="mysql")
        if len(statements) != 1 or not isinstance(statements[0], exp.Expression):
            raise S1SqlSecurityError("只允许单条 SELECT")
        tree = statements[0]
        if not isinstance(tree, (exp.Select, exp.Union, exp.With)) and tree.find(exp.Select) is None:
            raise S1SqlSecurityError("只允许 SELECT")
        if any(isinstance(node, (exp.Insert, exp.Update, exp.Delete, exp.Create, exp.Drop, exp.Alter)) for node in tree.walk()):
            raise S1SqlSecurityError("禁止非 SELECT 语句")
        aliases, _ = _tables(tree, snapshot)
        _expand_stars(tree, snapshot, aliases)
        if tree.find(exp.Star) is not None:
            raise S1SqlSecurityError("无法安全展开 SQL 中的星号字段")
        for rule in (function_rule.check(sql), depth_rule.check(sql), limit_rule.check(sql)):
            if not rule.passed:
                raise S1SqlSecurityError(rule.reason)
        if tree.find(exp.Limit) is None:
            tree = tree.limit(sandbox_config.max_result_rows, copy=False)
        allowed = resource_index(snapshot)
        used_tables, used_columns, trace, masked = _trace(tree, aliases, allowed)
        return S1SqlValidation(True, tree.sql(dialect="mysql"), used_tables, used_columns, trace, masked)
    except (sqlglot.errors.ParseError, S1SqlSecurityError) as exc:
        return S1SqlValidation(False, violations=[str(exc)])


def s1_parameter_name(reference: str) -> str:
    """Convert Java binding references to a legal SQLAlchemy named parameter."""
    if not reference or not re.fullmatch(r"[A-Za-z0-9_-]+", reference):
        raise S1SqlSecurityError("非法执行参数引用")
    return "iam_s1_" + re.sub(r"[^A-Za-z0-9_]", "_", reference)


def _placeholder(reference: str) -> exp.Placeholder:
    return exp.Placeholder(this=s1_parameter_name(reference))


def _predicate(column: exp.Column, predicate: dict[str, Any], bindings: dict[str, Any]) -> exp.Expression:
    reference = predicate.get("bindingReference") or predicate.get("parameterReference")
    operator = predicate.get("operatorCode")
    if operator in {"IS_NULL", "IS_NOT_NULL"}:
        return exp.Is(this=column, expression=exp.Null()) if operator == "IS_NULL" else exp.Not(this=exp.Is(this=column, expression=exp.Null()))
    if reference not in bindings:
        raise S1SqlSecurityError("记录条件执行绑定缺失")
    value = bindings[reference]
    placeholders: list[exp.Expression]
    if operator in {"IN", "NOT_IN"}:
        if not isinstance(value, list) or not value:
            raise S1SqlSecurityError("集合记录条件绑定不是非空数组")
        placeholders = [exp.Placeholder(this=f"{s1_parameter_name(reference)}_{index}") for index in range(len(value))]
        return exp.In(this=column, expressions=placeholders) if operator == "IN" else exp.Not(this=exp.In(this=column, expressions=placeholders))
    placeholder = _placeholder(reference)
    operators = {"EQ": exp.EQ, "NE": exp.NEQ, "GT": exp.GT, "GE": exp.GTE, "LT": exp.LT, "LE": exp.LTE}
    constructor = operators.get(operator)
    if constructor is None:
        raise S1SqlSecurityError("不支持的结构化记录条件操作符")
    return constructor(this=column, expression=placeholder)


def _grant_condition(resource: Any, grant: Any, alias: str, bindings: dict[str, Any]) -> exp.Expression | None:
    condition = grant.get("rowCondition")
    if not condition:
        return None
    predicates = []
    for raw in condition.get("predicates", []):
        field = raw.get("columnName")
        if field not in {column.get("name") for column in resource.get("columns", [])}:
            raise S1SqlSecurityError("记录条件字段不在当前资源快照")
        predicates.append(_predicate(exp.column(field, table=alias), raw, bindings))
    if not predicates:
        raise S1SqlSecurityError("记录条件为空")
    expression = predicates[0]
    for item in predicates[1:]:
        expression = exp.and_(expression, item, copy=False) if condition.get("matchType") == "ALL" else exp.or_(expression, item, copy=False)
    return expression


def inject_row_conditions(sql: str, snapshot: S1PermissionSnapshot, execution_bindings: list[S1ExecutionBinding]) -> tuple[str, dict[str, Any]]:
    validation = validate_sql(sql, snapshot)
    if not validation.passed:
        raise S1SqlSecurityError(validation.violations[0])
    tree = sqlglot.parse_one(validation.sql, dialect="mysql")
    aliases, _ = _tables(tree, snapshot)
    bindings = {item.reference: item.value for item in execution_bindings}
    resources = {item.tableName.lower(): item.model_dump() for item in snapshot.resources}
    for scope in traverse_scope(tree):
        select = scope.expression
        if not isinstance(select, exp.Select):
            continue
        for table_name, alias in _select_tables(select, aliases):
            resource = resources.get(table_name)
            if resource is None:
                raise S1SqlSecurityError("记录条件表不在 S1 快照")
            predicates = [
                condition for grant in resource["grantSources"]
                if (condition := _grant_condition(resource, grant, alias, bindings)) is not None
            ]
            if not predicates:
                continue
            policy = predicates[0]
            for item in predicates[1:]:
                policy = exp.or_(policy, item, copy=False)
            # Keep outer-join semantics.  The preserved side of a RIGHT JOIN
            # must be filtered in WHERE; putting it in ON would preserve rows
            # with NULLs on the non-preserved side.
            join = next((node for node in select.find_all(exp.Join)
                         if isinstance(node.this, exp.Table)
                         and (node.this.name or "").lower() == table_name
                         and (node.this.alias_or_name or table_name).lower() == alias), None)
            if join is not None and join.args.get("side") == "FULL":
                raise S1SqlSecurityError("FULL JOIN 无法安全注入 S1 行条件")
            if join is not None and join.args.get("side") == "RIGHT":
                join = None
            if join is not None:
                existing = join.args.get("on")
                join.set("on", exp.and_(existing, policy, copy=False) if existing is not None else policy)
            else:
                right_join = next((node for node in select.find_all(exp.Join)
                                   if node.args.get("side") == "RIGHT"
                                   and isinstance(select.args.get("from").this if select.args.get("from") else None, exp.Table)
                                   and (select.args["from"].this.name or "").lower() == table_name), None)
                if right_join is not None:
                    existing = right_join.args.get("on")
                    right_join.set("on", exp.and_(existing, policy, copy=False) if existing is not None else policy)
                else:
                    existing_where = select.args.get("where")
                    select.set("where", exp.Where(this=exp.and_(existing_where.this, policy, copy=False)) if existing_where else exp.Where(this=policy))
    rewritten = tree.sql(dialect="mysql")
    params: dict[str, Any] = {}
    names: dict[str, str] = {}
    for reference, value in bindings.items():
        parameter = s1_parameter_name(reference)
        previous = names.setdefault(parameter, reference)
        if previous != reference:
            raise S1SqlSecurityError("S1 执行参数引用发生名称冲突")
        if isinstance(value, list):
            for index, item in enumerate(value):
                params[f"{parameter}_{index}"] = item
        else:
            params[parameter] = value
    return rewritten, params
