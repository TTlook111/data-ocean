"""SQL execution node."""

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
        await sse.emit_progress(task_id, "SQL_EXECUTOR", "executing", estimate, state.get("retry_count", 0))

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
    tables = set()
    try:
        tree = sqlglot.parse_one(sql, dialect="mysql")
        for table in tree.find_all(exp.Table):
            if table.name:
                tables.add(table.name)
    except Exception:
        for match in re.finditer(r"\b(?:FROM|JOIN)\s+`?(\w+)`?", sql, re.IGNORECASE):
            tables.add(match.group(1))
    return sorted(tables)


def _extract_columns(sql: str) -> list[str]:
    try:
        tree = sqlglot.parse_one(sql, dialect="mysql")
    except Exception:
        return _extract_columns_fallback(sql)

    tables = _extract_tables(sql)
    aliases = _table_aliases(tree)
    single_table = tables[0] if len(tables) == 1 else ""
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
