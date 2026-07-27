"""SQL 执行节点

调用 sandbox 执行器在只读事务中执行已校验的 SQL，
返回查询结果数据行和列信息。

支持超时控制和取消检测。
"""

from __future__ import annotations

import logging
import re

import sqlglot
from sqlglot import exp

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
                corrected_sql = await call_llm(
                    system_prompt="你是 SQL 修正专家，根据错误信息修正 SQL 语法和表名列名。",
                    user_prompt=correction_prompt,
                    temperature=0.1,
                )
                corrected_sql = corrected_sql.strip()
                logger.info("LLM 自校正 task_id=%s original=%s corrected=%s",
                            task_id, sql[:60], corrected_sql[:60])
                return {
                    "generated_sql": corrected_sql,
                    "retry_count": retry_count,
                    "used_tables": used_tables,
                    "used_columns": used_columns,
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
            "error_message": result.error,
            "retry_count": retry_count,
            "used_tables": used_tables,
            "used_columns": used_columns,
            "current_node": "SQL_EXECUTOR",
        }

    logger.info("SQL execution finished task_id=%s rows=%d elapsed=%dms",
                task_id, result.row_count, result.execution_time_ms)

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
