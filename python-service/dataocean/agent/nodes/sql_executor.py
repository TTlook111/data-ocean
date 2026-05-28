"""SQL 执行节点

调用 009 模块的沙箱执行器，在只读事务中执行已校验的 SQL。
当前 Agent 流程中尚未传入数据源连接配置，执行功能待 Java 侧补充连接信息后生效。
"""

from __future__ import annotations

import logging
import re

from ..state import AgentState

logger = logging.getLogger(__name__)


async def run_sql_executor(state: AgentState) -> AgentState:
    """执行 SQL 查询

    从 validation_result 中获取改写后的 SQL，
    调用 009 模块沙箱执行器执行。
    当前 Agent 请求中未包含连接配置，返回明确提示。
    """
    validation_result = state.get("validation_result", {})
    sql = validation_result.get("rewritten_sql", "") or state.get("generated_sql", "")
    task_id = state.get("task_id", "")
    datasource_id = state.get("datasource_id", 0)

    logger.info("SQL 执行 task_id=%s datasource_id=%d sql=%s", task_id, datasource_id, sql[:80])

    if not sql:
        return {
            **state,
            "execution_result": {
                "columns": [],
                "data_rows": [],
                "row_count": 0,
                "execution_time_ms": 0,
                "error": "无可执行的 SQL",
            },
            "current_node": "SQL_EXECUTOR",
        }

    # 尝试从 state 中获取连接配置（由 Java 传入）
    connection_config = state.get("connection_config")
    if not connection_config:
        return {
            **state,
            "execution_result": {
                "columns": [],
                "data_rows": [],
                "row_count": 0,
                "execution_time_ms": 0,
                "error": "缺少数据源连接配置，无法执行 SQL",
            },
            "error_message": "缺少数据源连接配置，SQL 沙箱执行暂不可用",
            "used_tables": _extract_tables(sql),
            "used_columns": [],
            "current_node": "SQL_EXECUTOR",
        }

    # 调用 009 模块沙箱执行器
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
        return {
            **state,
            "execution_result": {
                "columns": [],
                "data_rows": [],
                "row_count": 0,
                "execution_time_ms": result.execution_time_ms,
                "error": result.error,
            },
            "error_message": result.error,
            "used_tables": _extract_tables(sql),
            "used_columns": [],
            "current_node": "SQL_EXECUTOR",
        }

    logger.info("SQL 执行完成 task_id=%s rows=%d elapsed=%dms",
                task_id, result.row_count, result.execution_time_ms)

    return {
        **state,
        "execution_result": {
            "columns": result.columns,
            "data_rows": result.rows,
            "row_count": result.row_count,
            "execution_time_ms": result.execution_time_ms,
            "error": None,
        },
        "used_tables": _extract_tables(sql),
        "used_columns": [],
        "current_node": "SQL_EXECUTOR",
    }


def _extract_tables(sql: str) -> list[str]:
    """从 SQL 中简单提取表名（FROM/JOIN 后的标识符）"""
    tables = set()
    for match in re.finditer(r"\b(?:FROM|JOIN)\s+`?(\w+)`?", sql, re.IGNORECASE):
        tables.add(match.group(1))
    return sorted(tables)
