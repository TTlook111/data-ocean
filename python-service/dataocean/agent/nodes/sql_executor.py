"""SQL 执行节点

调用 sandbox 执行器在只读事务中执行已校验的 SQL，
返回查询结果数据行和列信息。

支持超时控制和取消检测。
"""

from __future__ import annotations

import asyncio  # FIX #7: LLM 自校正超时控制
import json  # Phase 3 #18: 分块传输 JSON 序列化
import logging
import re

import sqlglot
from sqlglot import exp

from dataocean.core.error_messages import sanitize_error  # FIX #4: MySQL 错误脱敏

from .. import sse
from ..state import AgentState
from .sql_generator import estimate_execution_time

logger = logging.getLogger(__name__)


async def run_sql_executor(state: AgentState) -> AgentState:
    """Execute the validated SQL through the sandbox executor."""
    validation_result = state.get("validation_result", {})
    sql = validation_result.get("rewritten_sql", "") or state.get("generated_sql", "")
    task_id = state.get("task_id", "")
    datasource_id = state.get("datasource_id", 0)
    used_tables = _extract_tables(sql)
    used_columns = _extract_columns(sql)
    column_lineage = _extract_column_derivations(sql)  # Phase 1: 列级派生关系

    if sql:
        estimate = estimate_execution_time(sql)
        await sse.emit_progress(task_id, "SQL_EXECUTOR", "started", estimate, state.get("retry_count", 0))

    logger.info("SQL execution task_id=%s datasource_id=%d sql=%s", task_id, datasource_id, sql[:80])

    if not sql:
        return {
            "execution_result": {
                "columns": [],
                "data_rows": [],
                "row_count": 0,
                "execution_time_ms": 0,
                "error": "无可执行的 SQL",
            },
            "current_node": "SQL_EXECUTOR",
            "column_lineage": [],
        }

    connection_config = state.get("connection_config")
    if not connection_config:
        return {
            "execution_result": {
                "columns": [],
                "data_rows": [],
                "row_count": 0,
                "execution_time_ms": 0,
                "error": "缺少数据源连接配置，无法执行 SQL",
            },
            "error_message": "缺少数据源连接配置，SQL 沙箱执行暂不可用",
            "used_tables": used_tables,
            "used_columns": used_columns,
            "column_lineage": column_lineage,
            "current_node": "SQL_EXECUTOR",
        }

    from dataocean.sandbox.executor import execute as sandbox_execute
    from dataocean.infra.llm import call_llm, LLMException  # Phase 2 #11: LLM 自校正

    mask_columns = validation_result.get("masked_fields", {})
    result = await sandbox_execute(
        sql=sql,
        datasource_id=datasource_id,
        connection_config=connection_config,
        mask_columns=mask_columns,
        task_id=task_id,
    )

    if not result.success:
        # 安全修复：执行失败时递增 retry_count
        retry_count = state.get("retry_count", 0) + 1

        # Phase 2 #11: LLM 自校正——只对可修正错误尝试，不修正 timeout/connection_error
        error_lower = (result.error or "").lower()
        is_correctable = any(keyword in error_lower for keyword in
            ("syntax", "table", "column", "unknown", "doesn't exist", "does not exist",
             "parse error", "invalid"))
        max_retries = state.get("agent_config", {}).get("max_retries", 2) if isinstance(
            state.get("agent_config"), dict) else 2

        if is_correctable and retry_count <= max_retries:
            try:
                correction_prompt = f"""SQL 执行失败。
错误信息：{result.error}
原始 SQL：{sql}
Schema 上下文：{state.get('schema_context', [])}

请修正 SQL。只输出修正后的 SQL，不要任何解释。"""
                corrected_sql = await asyncio.wait_for(
                    call_llm(
                        system_prompt="你是 SQL 修正专家，根据错误信息修正 SQL 语法和表名列名。",
                        user_prompt=correction_prompt,
                        temperature=0.1,
                    ),
                    timeout=10.0  # FIX #7: LLM 自校正独立超时 10s，避免耗尽总预算
                )
                corrected_sql = corrected_sql.strip()
                logger.info("LLM 自校正 task_id=%s original=%s corrected=%s",
                            task_id, sql[:60], corrected_sql[:60])
                return {
                    "generated_sql": corrected_sql,
                    "retry_count": retry_count,
                    "used_tables": used_tables,
                    "used_columns": used_columns,
                    "column_lineage": column_lineage,
                    "current_node": "SQL_EXECUTOR",
                }
            except (LLMException, Exception) as e:
                logger.warning("LLM 自校正失败 task_id=%s error=%s", task_id, e)
                # 自校正失败不阻断主流程，走已有重试路由

        return {
            "execution_result": {
                "columns": [],
                "data_rows": [],
                "row_count": 0,
                "execution_time_ms": result.execution_time_ms,
                "error": result.error,
            },
            "error_message": sanitize_error(result.error),  # FIX #4: 脱敏后再返回前端
            "retry_count": retry_count,
            "used_tables": used_tables,
            "used_columns": used_columns,
            "column_lineage": column_lineage,
            "current_node": "SQL_EXECUTOR",
        }

    logger.info("SQL execution finished task_id=%s rows=%d elapsed=%dms",
                task_id, result.row_count, result.execution_time_ms)

    # Phase 3 #18: 大结果集分块传输（>200 行时分块通过 SSE 发送首屏数据）
    if result.rows and len(result.rows) > 200:
        try:
            CHUNK_SIZE = 100
            total = len(result.rows)
            total_chunks = (total + CHUNK_SIZE - 1) // CHUNK_SIZE
            for i in range(total_chunks):
                chunk = result.rows[i * CHUNK_SIZE:(i + 1) * CHUNK_SIZE]
                await sse.emit_progress(
                    task_id, "RESULT_CHUNK", "completed",
                    json.dumps({"chunk_index": i, "total_chunks": total_chunks,
                                "is_last": i == total_chunks - 1,
                                "rows": chunk}),
                    state.get("retry_count", 0))
            logger.info("大结果分块完成 task_id=%s chunks=%d rows=%d", task_id, total_chunks, total)
        except Exception as e:
            logger.warning("结果分块发送失败 task_id=%s error=%s", task_id, e)
            # 分块失败不阻断主流程

    return {
        "execution_result": {
            "columns": result.columns,
            "data_rows": result.rows,
            "row_count": result.row_count,
            "execution_time_ms": result.execution_time_ms,
            "error": None,
        },
        "used_tables": used_tables,
        "used_columns": used_columns,
        "column_lineage": column_lineage,
        "current_node": "SQL_EXECUTOR",
    }


def _extract_tables(sql: str) -> list[str]:
    """从 SQL 中提取表名列表

    使用 sqlglot AST 解析提取表名，失败时 fallback 到正则匹配。
    返回排序后的表名列表（去重）。
    """
    tables = set()
    try:
        # 使用 sqlglot 解析 SQL AST，遍历所有 Table 节点
        tree = sqlglot.parse_one(sql, dialect="mysql")
        for table in tree.find_all(exp.Table):
            if table.name:
                tables.add(table.name)
    except Exception:
        # Fallback：使用正则匹配 FROM/JOIN 后的表名
        for match in re.finditer(r"\b(?:FROM|JOIN)\s+`?(\w+)`?", sql, re.IGNORECASE):
            tables.add(match.group(1))
    return sorted(tables)


def _extract_columns(sql: str) -> list[str]:
    """从 SQL 中提取列名列表

    使用 sqlglot AST 解析提取列名，处理表别名和限定名。
    返回排序后的列名列表（去重）。
    """
    try:
        tree = sqlglot.parse_one(sql, dialect="mysql")
    except Exception:
        return _extract_columns_fallback(sql)

    tables = _extract_tables(sql)
    aliases = _table_aliases(tree)
    single_table = tables[0] if len(tables) == 1 else ""
    # 收集有表限定符的列，用于后续判断列归属
    qualified_tables_by_column: dict[str, set[str]] = {}
    for column in tree.find_all(exp.Column):
        if column.name and column.table:
            qualified_tables_by_column.setdefault(column.name, set()).add(aliases.get(column.table, column.table))

    columns: set[str] = set()
    for column in tree.find_all(exp.Column):
        name = column.name
        if not name or name == "*":
            continue
        qualifier = column.table
        if qualifier:
            table = aliases.get(qualifier, qualifier)
        elif single_table:
            table = single_table
        elif len(qualified_tables_by_column.get(name, set())) == 1:
            table = next(iter(qualified_tables_by_column[name]))
        else:
            table = "__UNRESOLVED__"
        columns.add(f"{table}.{name}" if table else name)
    return sorted(columns)


def _table_aliases(tree: exp.Expression) -> dict[str, str]:
    aliases = {}
    for table in tree.find_all(exp.Table):
        if not table.name:
            continue
        aliases[table.name] = table.name
        alias = table.alias
        if alias:
            aliases[alias] = table.name
    return aliases


def _extract_columns_fallback(sql: str) -> list[str]:
    columns = set()
    for match in re.finditer(r"`?(\w+)`?\.`?(\w+)`?", sql):
        columns.add(f"{match.group(1)}.{match.group(2)}")
    return sorted(columns)


# ---------------------------------------------------------------------------
# Phase 1: 列级派生关系提取（DERIVED_FROM）
# 参考 Spline derivesFrom + computedBy 双通道模式
# ---------------------------------------------------------------------------

# 聚合函数名集合（sqlglot exp.Avg, exp.Sum 等已有类型，这里用于字符串 fallback）
_AGGREGATE_NAMES = frozenset({
    "sum", "count", "avg", "average", "min", "max",
    "group_concat", "stddev", "variance", "std", "var",
})


def _extract_column_derivations(sql: str) -> list[dict]:
    """从 SQL SELECT 子句提取列级派生关系

    参考 DataHub sqlglot schema-aware 解析和 Spline 的 derivesFrom 模式：
    对于每个 SELECT 输出表达式，找到其引用的源表列并记录转换类型。

    返回格式与 schema.ColumnDerivation 一致（dict 形式便于放入 AgentState）：
    [
      {
        "targetTable": "daily_stats",
        "targetColumn": "total",
        "targetAlias": "total_amount",
        "sourceTable": "orders",
        "sourceColumn": "amount",
        "expression": "amount * unit_price",
        "expressionType": "ARITHMETIC"
      },
      ...
    ]
    """
    try:
        tree = sqlglot.parse_one(sql, dialect="mysql")
    except Exception:
        return []

    # 预处理：构建别名映射和表列表
    aliases = _table_aliases(tree)
    tables = _extract_tables(sql)

    # 如果存在 set 操作（UNION/INTERSECT/EXCEPT），递归处理每个子查询
    if isinstance(tree, (exp.Union, exp.Intersect, exp.Except)):
        derivations: list[dict] = []
        for child in tree.expressions:
            derivations.extend(_extract_column_derivations(child.sql(dialect="mysql")))
        return derivations

    # 定位 SELECT 语句：可能在顶层、CTE 内或子查询内
    select = tree.find(exp.Select)
    if select is None:
        return []

    return _extract_select_derivations(select, tables, aliases)


def _extract_select_derivations(
    select: exp.Select,
    tables: list[str],
    aliases: dict[str, str],
) -> list[dict]:
    """从单个 SELECT 语句提取列派生关系"""
    derivations: list[dict] = []
    single_table = tables[0] if len(tables) == 1 else ""

    # 确定目标表名（当前 SELECT 对应的表）
    # 从最外层 FROM 子句获取
    from_exp = select.find(exp.From)
    target_table = ""
    if from_exp and from_exp.this:
        target_table = _resolve_table_name(from_exp.this, aliases)

    # 遍历 SELECT 中的每个输出表达式
    for select_expr in select.expressions:
        if not isinstance(select_expr, exp.Alias) and not isinstance(select_expr, (exp.Column, exp.Literal)):
            # 非别名的复杂表达式（如 func(col)），sqlglot 可能不包装为 Alias
            pass

        # 获取别名（AS name）
        output_alias = select_expr.alias if isinstance(select_expr, exp.Alias) else None

        # 获取实际的表达式（如果是 Alias，取其内部表达式）
        expr = select_expr.this if isinstance(select_expr, exp.Alias) else select_expr

        if isinstance(expr, exp.Star):
            # SELECT * 不产生列级派生
            continue

        # 确定表达式类型和表达式文本
        expression_type = _classify_expression(expr)
        expression_text = _get_expression_text(expr)

        # 提取所有引用的列
        leaf_columns = list(expr.find_all(exp.Column))

        if not leaf_columns:
            # 纯常量/字面量，无派生关系
            continue

        # 输出列名
        output_col = output_alias or (_get_column_output_name(expr, leaf_columns))

        for col in leaf_columns:
            col_name = col.name
            if not col_name or col_name == "*":
                continue

            # 解析源表
            qualifier = col.table
            if qualifier:
                source_table = aliases.get(qualifier, qualifier)
            elif single_table:
                source_table = single_table
            else:
                source_table = target_table or "__UNRESOLVED__"

            # 引用同一个源列的表达式（如子查询）
            actual_target_table = target_table or source_table

            derivations.append({
                "targetTable": actual_target_table,
                "targetColumn": output_col.replace("`", "").strip(),
                "targetAlias": output_alias,
                "sourceTable": source_table,
                "sourceColumn": col_name.replace("`", "").strip(),
                "expression": expression_text,
                "expressionType": expression_type,
            })

    return derivations


def _resolve_table_name(table_exp, aliases: dict[str, str]) -> str:
    """解析表表达式中的实际表名"""
    if isinstance(table_exp, exp.Table):
        name = table_exp.name
        return aliases.get(name, name) if name else ""
    # 子查询、CTE 等
    alias = getattr(table_exp, 'alias', None)
    return alias if alias else ""


def _classify_expression(expr: exp.Expression) -> str:
    """判断表达式的计算类型（映射到 expression_type 枚举）

    DataOcean expression_type 枚举值：
    DIRECT, ARITHMETIC, AGGREGATION, CONCAT, CASE_WHEN, CAST
    """
    if isinstance(expr, exp.Column):
        return "DIRECT"

    # 聚合函数：sqlglot 有专门的 AggFunc 基类
    if isinstance(expr, (exp.AggFunc, exp.Avg, exp.Sum, exp.Count, exp.Max, exp.Min,
                          exp.Std, exp.StdDev, exp.Variance, exp.GroupConcat)):
        return "AGGREGATION"

    # SQL 函数（检查是否是已知聚合函数名）
    if isinstance(expr, exp.Func):
        func_name = (expr.sql_name() or "").lower()
        if func_name in _AGGREGATE_NAMES:
            return "AGGREGATION"
        if func_name in ("concat", "concat_ws", "group_concat"):
            return "CONCAT"
        if func_name in ("cast", "convert"):
            return "CAST"

    # CASE WHEN 表达式
    if isinstance(expr, (exp.Case, exp.If)):
        return "CASE_WHEN"

    # 算术运算
    if isinstance(expr, (exp.Add, exp.Sub, exp.Mul, exp.Div, exp.Mod, exp.Pow)):
        return "ARITHMETIC"

    # 字符串拼接操作符
    if isinstance(expr, exp.Concat):
        return "CONCAT"

    # 类型转换
    if isinstance(expr, exp.Cast):
        return "CAST"

    # 二元运算（默认算作 ARITHMETIC）
    if isinstance(expr, exp.Binary):
        return "ARITHMETIC"

    # 一元运算（如 -col）
    if isinstance(expr, exp.Unary):
        return "ARITHMETIC"

    # 嵌套 Alias
    if isinstance(expr, exp.Alias):
        return _classify_expression(expr.this)

    # 兜底：字面量、Bracket 等
    return "DIRECT"


def _get_expression_text(expr: exp.Expression) -> str | None:
    """获取表达式的 SQL 文本表示"""
    if isinstance(expr, exp.Column):
        # 纯列引用不返回表达式
        return None
    try:
        text = expr.sql(dialect="mysql")
        return text[:200] if text else None  # 截断过长表达式
    except Exception:
        return None


def _get_column_output_name(expr: exp.Expression, leaf_columns: list[exp.Column]) -> str:
    """当无别名时，推导输出列名"""
    if isinstance(expr, exp.Column):
        return expr.name or "unknown"
    # 函数调用：用函数名
    if isinstance(expr, exp.Func):
        return expr.sql_name() or "computed"
    # 从第一个叶列推导
    if leaf_columns:
        first = leaf_columns[0]
        return first.name or "computed"
    return "computed"
