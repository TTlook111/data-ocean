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


# B3 强制执行的字段使用位置。GROUP/ORDER/HAVING/FUNCTION/SUBQUERY 在合同
# 中记录为暂缓细分，因此这里不做拦截；PROJECTION 决定字段值能否被暴露，
# FILTER/JOIN 决定字段能否参与收窄条件。
_ENFORCED_USAGE_ROLES = frozenset({"PROJECTION", "FILTER", "JOIN"})

_SET_OPERATIONS = (exp.Union, exp.Intersect, exp.Except)


@dataclass
class S1SqlValidation:
    passed: bool
    sql: str = ""
    used_tables: list[str] = field(default_factory=list)
    used_columns: list[str] = field(default_factory=list)
    source_trace: list[dict[str, Any]] = field(default_factory=list)
    masked_fields: dict[str, str] = field(default_factory=dict)
    violations: list[str] = field(default_factory=list)


# --------------------------------------------------------------------------
# Scope helpers
#
# Every name (table alias, CTE name, derived-table alias) must be resolved in
# the scope that actually owns it.  A global alias map lets an inner subquery
# redefine an outer alias and makes the validator authorise one table while the
# database reads another, so all resolution below is scope-local.
# --------------------------------------------------------------------------


def _scope_index(tree: exp.Expression) -> dict[int, Any]:
    """Map each query node to its sqlglot scope."""
    try:
        return {id(scope.expression): scope for scope in traverse_scope(tree)}
    except S1SqlSecurityError:
        raise
    except Exception as exc:  # fail-closed: an unresolved scope cannot be trusted
        raise S1SqlSecurityError(f"无法解析 SQL 作用域: {exc}") from exc


def _scope_of(node: exp.Expression | None, scopes: dict[int, Any]) -> Any | None:
    """Return the innermost scope that encloses ``node``."""
    while node is not None:
        scope = scopes.get(id(node))
        if scope is not None:
            return scope
        node = node.parent
    return None


def _local_sources(scope: Any) -> dict[str, tuple[exp.Expression, Any]]:
    """Alias to source for this scope only; nested scopes are never included."""
    return {str(alias).lower(): value for alias, value in scope.selected_sources.items()}


def _physical_sources(scope: Any) -> list[tuple[str, exp.Table]]:
    """Direct physical tables of this scope as (alias used here, table node).

    A CTE or derived-table reference also appears in ``selected_sources`` but
    its source is a Scope, not a Table, so it is excluded here.
    """
    return [
        (str(alias).lower(), value[1])
        for alias, value in scope.selected_sources.items()
        if isinstance(value[1], exp.Table)
    ]


def _is_set_operation(node: exp.Expression) -> bool:
    return isinstance(node, _SET_OPERATIONS)


def _branch_scopes(scope: Any, scopes: dict[int, Any]) -> list[Any]:
    """Direct branch scopes of a set operation, flattening nested set operations."""
    result: list[Any] = []
    for key in ("this", "expression"):
        child = scope.expression.args.get(key)
        child_scope = scopes.get(id(child)) if child is not None else None
        if child_scope is None:
            continue
        if _is_set_operation(child_scope.expression):
            result.extend(_branch_scopes(child_scope, scopes))
        else:
            result.append(child_scope)
    return result


def _validate_tables(scopes: dict[int, Any], snapshot: S1PermissionSnapshot) -> None:
    """Reject unauthorised, cross-database and missing table references."""
    allowed = set(resource_index(snapshot))
    physical: set[str] = set()
    for scope in scopes.values():
        for _, source in _physical_sources(scope):
            if source.args.get("catalog") is not None or source.args.get("db") is not None:
                raise S1SqlSecurityError(f"禁止跨库引用表: {source.sql(dialect='mysql')}")
            name = (source.name or "").lower()
            if name:
                physical.add(name)
    if not physical:
        raise S1SqlSecurityError("SQL 必须引用 S1 授权表")
    unknown = physical - allowed
    if unknown:
        raise S1SqlSecurityError(f"引用了未授权的表: {', '.join(sorted(unknown))}")


def _validate_set_operations(scopes: dict[int, Any]) -> None:
    """Every branch of a set operation must expose the same number of columns.

    Branches align by position, so a mismatch is invalid SQL; both directions
    must be rejected here rather than left to the database.
    """
    for scope in scopes.values():
        if not _is_set_operation(scope.expression):
            continue
        counts = {
            len(branch.expression.expressions)
            for branch in _branch_scopes(scope, scopes)
            if isinstance(branch.expression, exp.Select)
        }
        if len(counts) > 1:
            raise S1SqlSecurityError("集合运算各分支列数不一致")


def _declared_usages(meta: dict[str, Any]) -> set[str]:
    """Return the field's declared usage positions in upper case."""
    return {str(item).upper() for item in meta.get("usage") or []}


def _expand_stars(scopes: dict[int, Any], snapshot: S1PermissionSnapshot) -> None:
    allowed = resource_index(snapshot)
    for scope in scopes.values():
        select = scope.expression
        if not isinstance(select, exp.Select):
            continue
        local = _local_sources(scope)
        physical = _physical_sources(scope)
        replacements: list[exp.Expression] = []
        changed = False
        for expression in list(select.expressions):
            table_node: exp.Table | None = None
            if isinstance(expression, exp.Star):
                if len(local) != 1 or len(physical) != 1:
                    raise S1SqlSecurityError("无法安全展开 SELECT *")
                table_node = physical[0][1]
            elif isinstance(expression, exp.Column) and expression.name == "*":
                entry = local.get((expression.table or "").lower())
                if entry is None or not isinstance(entry[1], exp.Table):
                    raise S1SqlSecurityError("无法安全展开 table.*")
                table_node = entry[1]
            if table_node is None:
                replacements.append(expression)
                continue
            table_name = (table_node.name or "").lower()
            alias = next((name for name, node in physical if node is table_node), table_name)
            # 星号只展开"可投影"字段：未声明 PROJECTION 的字段（例如仅用于过滤）
            # 不会被静默带进结果集，与 HIDDEN 字段的处理保持一致。
            projectable = [
                meta for meta in allowed[table_name].values()
                if "PROJECTION" in _declared_usages(meta)
            ]
            if not projectable:
                raise S1SqlSecurityError(f"表 {table_name} 没有可投影字段，无法展开星号")
            for column in projectable:
                replacements.append(exp.column(column["name"], table=alias))
            changed = True
        if changed:
            select.set("expressions", replacements)


# --------------------------------------------------------------------------
# Column resolution
# --------------------------------------------------------------------------


def _output_position(scope: Any, name: str, scopes: dict[int, Any]) -> int | None:
    """Position of ``name`` among a set operation's output columns.

    SQL takes a set operation's result column names from its **first** branch,
    and every branch feeds the same positions, so an outer reference must be
    matched positionally rather than by looking for a per-branch alias.
    """
    inner = scope.expression
    if _is_set_operation(inner):
        branches = _branch_scopes(scope, scopes)
        return _output_position(branches[0], name, scopes) if branches else None
    if not isinstance(inner, exp.Select):
        return None
    for index, output in enumerate(inner.expressions):
        if (output.alias_or_name or "").lower() == name:
            return index
    return None


def _sources_of(output: exp.Expression, allowed: dict[str, dict[str, Any]],
                scopes: dict[int, Any], seen: frozenset[int]) -> list[tuple[str, str]]:
    """Physical sources feeding one projection expression."""
    resolved: set[tuple[str, str]] = set()
    for column in output.find_all(exp.Column):
        resolved.update(_resolve_column(column, _scope_of(column, scopes), allowed, scopes, seen))
    return sorted(resolved)


def _position_sources(scope: Any | None, index: int, allowed: dict[str, dict[str, Any]],
                      scopes: dict[int, Any], seen: frozenset[int]) -> list[tuple[str, str]]:
    """Physical sources feeding output position ``index`` summed over all branches."""
    if scope is None or id(scope) in seen:
        raise S1SqlSecurityError("集合运算输出无法追踪字段来源")
    inner = scope.expression
    if _is_set_operation(inner):
        resolved: set[tuple[str, str]] = set()
        for branch in _branch_scopes(scope, scopes):
            resolved.update(_position_sources(branch, index, allowed, scopes, seen | {id(scope)}))
        return sorted(resolved)
    if not isinstance(inner, exp.Select):
        return []
    if index >= len(inner.expressions):
        raise S1SqlSecurityError("集合运算各分支列数不一致")
    sources = _sources_of(inner.expressions[index], allowed, scopes, seen)
    if not sources:
        # 常量或其它无字段来源的分支输出不能算作"已完整追踪"：Java 的
        # hasCompleteSourceTrace 会因空来源拒绝结果，那时光标已经跑完整个
        # 查询。此处提前拒绝，避免先执行再失败。
        raise S1SqlSecurityError("集合运算分支的输出没有可追踪字段来源")
    return sources


def _resolve_virtual(
    scope: Any | None,
    name: str,
    alias: str,
    allowed: dict[str, dict[str, Any]],
    scopes: dict[int, Any],
    seen: frozenset[int],
) -> list[tuple[str, str]]:
    """Resolve ``alias.name`` through a CTE or derived table to physical sources."""
    if scope is None or id(scope) in seen:
        raise S1SqlSecurityError(f"无法追踪字段来源: {alias}.{name}")
    inner = scope.expression
    if _is_set_operation(inner):
        # UNION/INTERSECT/EXCEPT 按列位置对齐：外层引用由**每个**分支的同一位置
        # 供给，只认第一分支的名字或首个匹配项会漏掉其它分支的来源，例如脱敏字段。
        index = _output_position(scope, name, scopes)
        if index is None:
            return []
        return _position_sources(scope, index, allowed, scopes, seen)
    if not isinstance(inner, exp.Select):
        return []
    for output in inner.expressions:
        if (output.alias_or_name or "").lower() == name:
            return _sources_of(output, allowed, scopes, seen | {id(scope)})
    return []


def _resolve_source(
    entry: tuple[exp.Expression, Any],
    name: str,
    alias: str,
    allowed: dict[str, dict[str, Any]],
    scopes: dict[int, Any],
    seen: frozenset[int],
) -> list[tuple[str, str]]:
    """Return physical sources for one source of the current scope.

    An empty list means "this source does not expose that column"; the caller
    decides whether that is an ambiguity error or an authorisation failure.
    """
    _, source = entry
    if isinstance(source, exp.Table):
        table = (source.name or "").lower()
        if table not in allowed or name not in allowed[table]:
            return []
        return [(table, name)]
    return _resolve_virtual(source, name, alias, allowed, scopes, seen)


def _resolve_in_set_operation(
    column: exp.Column,
    name: str,
    qualifier: str,
    scope: Any,
    allowed: dict[str, dict[str, Any]],
    scopes: dict[int, Any],
    seen: frozenset[int],
) -> list[tuple[str, str]]:
    """Resolve a set-operation level reference such as its ORDER BY.

    Its output names come from the first branch and the reference addresses a
    column position, which every branch feeds.
    """
    branches = _branch_scopes(scope, scopes)
    if qualifier and not any(qualifier in _local_sources(branch) for branch in branches):
        raise S1SqlSecurityError(f"无法追踪字段来源: {qualifier}.{name}")
    index = _output_position(scope, name, scopes)
    if index is None:
        raise S1SqlSecurityError(f"无法唯一追踪未限定字段: {name}")
    return _position_sources(scope, index, allowed, scopes, seen)


def _resolve_column(
    column: exp.Column,
    scope: Any | None,
    allowed: dict[str, dict[str, Any]],
    scopes: dict[int, Any],
    seen: frozenset[int],
) -> list[tuple[str, str]]:
    """Resolve one column reference inside the scope that owns it."""
    name = (column.name or "").lower()
    qualifier = (column.table or "").lower()
    if not name or name == "*":
        raise S1SqlSecurityError("SQL 中仍存在未展开的星号字段")
    if scope is None:
        raise S1SqlSecurityError(f"无法追踪字段来源: {column.sql(dialect='mysql')}")
    local = _local_sources(scope)
    if not local and _is_set_operation(scope.expression):
        return _resolve_in_set_operation(column, name, qualifier, scope, allowed, scopes, seen)
    if qualifier:
        entry = local.get(qualifier)
        if entry is None:
            raise S1SqlSecurityError(f"无法追踪字段来源: {qualifier}.{name}")
        resolved = _resolve_source(entry, name, qualifier, allowed, scopes, seen)
        if not resolved:
            raise S1SqlSecurityError(f"引用了未授权字段: {qualifier}.{name}")
        return resolved
    matches: set[tuple[str, str]] = set()
    for alias, entry in local.items():
        matches.update(_resolve_source(entry, name, alias, allowed, scopes, seen))
    unique = sorted(matches)
    if len(unique) != 1:
        raise S1SqlSecurityError(f"无法唯一追踪未限定字段: {name}")
    return unique


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


def _usage_role(column: exp.Column) -> str | None:
    """Return the enforced clause position of a column reference.

    ``None`` means the reference sits in a position B3 does not enforce yet
    (GROUP / ORDER / HAVING / 子查询与窗口内的其他位置）。
    """
    node: exp.Expression = column
    while True:
        parent = node.parent
        if parent is None:
            return None
        if isinstance(parent, exp.Where):
            return "FILTER"
        if isinstance(parent, exp.Join):
            return "JOIN"
        if isinstance(parent, (exp.Group, exp.Having, exp.Order)):
            return None
        if isinstance(parent, exp.Select):
            return "PROJECTION" if any(node is item for item in parent.expressions) else None
        node = parent


def _enforce_usage(column: exp.Column, table: str, name: str, allowed: dict[str, dict[str, Any]]) -> None:
    """Reject a reference whose clause position was not declared for the field."""
    role = _usage_role(column)
    if role is None or role not in _ENFORCED_USAGE_ROLES:
        return
    if role not in _declared_usages(allowed[table][name]):
        raise S1SqlSecurityError(f"字段 {table}.{name} 未声明 {role} 使用位置")


def _trace(tree: exp.Expression, scopes: dict[int, Any], allowed: dict[str, dict[str, Any]],
           enforce_usage: bool = True) -> tuple[list[str], list[str], list[dict[str, Any]], dict[str, str]]:
    used_tables: set[str] = set()
    used_columns: set[str] = set()
    trace: list[dict[str, Any]] = []
    # 每个输出列名对应的脱敏策略集合。最终脱敏按列名生效，Java 侧
    # `maskResultByFields` 会把脱敏映射的键与数据列名都转小写后再匹配，因此
    # 这里也必须用同一套规范化（lower）判定冲突，否则 `phone AS X` 与
    # `email AS x` 会被当成两个键，落到 Java 后撞成同一个键并静默取其一。
    mask_policies: dict[str, set[str]] = {}
    mask_output_names: dict[str, str] = {}
    for column in tree.find_all(exp.Column):
        for table, name in _resolve_column(column, _scope_of(column, scopes), allowed, scopes, frozenset()):
            _protection(column, table, name, allowed)
            if enforce_usage:
                _enforce_usage(column, table, name, allowed)
            used_tables.add(table)
            used_columns.add(f"{table}.{name}")
    for scope in scopes.values():
        node = scope.expression
        entries: list[tuple[str, str, list[tuple[str, str]]]] = []
        if isinstance(node, exp.Select):
            for output in node.expressions:
                entries.append((
                    output.alias_or_name or output.sql(dialect="mysql"),
                    output.sql(dialect="mysql"),
                    _sources_of(output, allowed, scopes, frozenset()),
                ))
        elif _is_set_operation(node):
            # 集合运算的结果列名取自第一分支，但该位置由**每个**分支供给。
            # 不做汇总时，外层列只会记录第一分支的来源，脱敏字段会漏标。
            branches = _branch_scopes(scope, scopes)
            first = branches[0] if branches else None
            if first is None or not isinstance(first.expression, exp.Select):
                continue
            for index, output in enumerate(first.expression.expressions):
                entries.append((
                    output.alias_or_name or output.sql(dialect="mysql"),
                    output.sql(dialect="mysql"),
                    _position_sources(scope, index, allowed, scopes, frozenset()),
                ))
        else:
            continue
        for output_name, expression_sql, sources in entries:
            for table, name in sources:
                meta = allowed[table][name]
                if meta.get("protectionLevel") != "MASKED":
                    continue
                key = output_name.lower()
                mask_output_names.setdefault(key, output_name)
                found = mask_policies.setdefault(key, set())
                policy = str(meta.get("maskPolicy") or "")
                if policy:
                    found.add(policy)
            trace.append({
                "outputColumn": output_name,
                "sources": sorted({f"{table}.{name}" for table, name in sources}),
                "expression": expression_sql,
            })
    conflicts = {name: sorted(found) for name, found in mask_policies.items() if len(found) > 1}
    if conflicts:
        detail = "; ".join(f"{name} -> {', '.join(found)}" for name, found in sorted(conflicts.items()))
        raise S1SqlSecurityError(f"同一输出列汇入多种脱敏策略，拒绝执行: {detail}")
    masked = {
        mask_output_names[name]: (next(iter(found)) if found else "")
        for name, found in mask_policies.items()
    }
    return sorted(used_tables), sorted(used_columns), trace, masked


def _top_level_query(tree: exp.Expression) -> exp.Expression:
    """Return the statement's own query node.

    A bare parenthesised query is unwrapped so that an injected LIMIT lands on
    the query expression instead of outside the parentheses.
    """
    node: exp.Expression = tree
    while isinstance(node, (exp.Subquery, exp.Paren)) and isinstance(node.this, exp.Expression):
        node = node.this
    return node


def validate_sql(sql: str, snapshot: S1PermissionSnapshot, *, enforce_usage: bool = True) -> S1SqlValidation:
    """Validate one SELECT against the current S1 snapshot.

    ``enforce_usage`` is only turned off when re-validating SQL that already
    carries server-injected row-condition predicates: those predicates are
    authored by the server and their clause position is forced by join
    semantics, so they must not be measured against the requester's declared
    field usages.  Model-authored SQL is always validated with it on.
    """
    try:
        statements = sqlglot.parse(sql, dialect="mysql")
        if len(statements) != 1 or not isinstance(statements[0], exp.Expression):
            raise S1SqlSecurityError("只允许单条 SELECT")
        tree = statements[0]
        if not isinstance(tree, (exp.Select, exp.Union, exp.With)) and tree.find(exp.Select) is None:
            raise S1SqlSecurityError("只允许 SELECT")
        if any(isinstance(node, (exp.Insert, exp.Update, exp.Delete, exp.Create, exp.Drop, exp.Alter)) for node in tree.walk()):
            raise S1SqlSecurityError("禁止非 SELECT 语句")
        scopes = _scope_index(tree)
        _validate_tables(scopes, snapshot)
        _validate_set_operations(scopes)
        _expand_stars(scopes, snapshot)
        if tree.find(exp.Star) is not None:
            raise S1SqlSecurityError("无法安全展开 SQL 中的星号字段")
        for rule in (function_rule.check(sql), depth_rule.check(sql), limit_rule.check(sql)):
            if not rule.passed:
                raise S1SqlSecurityError(rule.reason)
        # 只判断最外层查询自身的 LIMIT：子查询、派生表或 UNION 分支里的 LIMIT
        # 不能代表外层结果集已经被封顶。
        query = _top_level_query(tree)
        if query.args.get("limit") is None:
            query = query.limit(sandbox_config.max_result_rows, copy=False)
        if query.args.get("limit") is None:
            raise S1SqlSecurityError("无法为 SQL 注入行数上限")
        allowed = resource_index(snapshot)
        used_tables, used_columns, trace, masked = _trace(tree, scopes, allowed, enforce_usage)
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
    scopes = _scope_index(tree)
    bindings = {item.reference: item.value for item in execution_bindings}
    resources = {item.tableName.lower(): item.model_dump() for item in snapshot.resources}
    for scope in scopes.values():
        select = scope.expression
        if not isinstance(select, exp.Select):
            continue
        # 只处理该作用域自己的物理源：嵌套子查询里的表由它自己的作用域注入，
        # 否则会生成外层并不存在的表限定条件。
        for alias, table_node in _physical_sources(scope):
            table_name = (table_node.name or "").lower()
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
            joins = [node for node in (select.args.get("joins") or []) if isinstance(node, exp.Join)]
            if any(node.args.get("side") == "FULL" for node in joins):
                raise S1SqlSecurityError("FULL JOIN 无法安全注入 S1 行条件")
            join = next((node for node in joins
                         if isinstance(node.this, exp.Table)
                         and (node.this.alias_or_name or "").lower() == alias), None)

            def append_to_on(node: exp.Join) -> None:
                existing = node.args.get("on")
                node.set("on", exp.and_(existing, policy, copy=False) if existing is not None else policy)

            # Keep outer-join semantics.  A JOIN ON condition does not filter the
            # preserved (right) side of a RIGHT JOIN, so a policy for that table
            # must go to WHERE; a policy for the FROM side of a RIGHT JOIN stays
            # in ON so that row-preservation semantics survive.  A join without
            # its own ON (comma / cross join, or USING-only) must not get a
            # fabricated ON clause, so it falls back to WHERE.
            if join is not None and join.args.get("side") != "RIGHT" and join.args.get("on") is not None:
                append_to_on(join)
                continue
            if join is None:
                right_join = next((node for node in joins if node.args.get("side") == "RIGHT"), None)
                if right_join is not None and right_join.args.get("on") is not None:
                    append_to_on(right_join)
                    continue
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
