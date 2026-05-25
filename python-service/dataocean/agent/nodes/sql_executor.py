"""SQL 执行节点

调用 009 模块的 SQL 沙箱执行逻辑。
当 009 模块完整实现后，此节点将调用其内部函数执行 SQL。
当前阶段返回 NOT_IMPLEMENTED 错误，明确告知调用方沙箱尚未接入。
"""

from __future__ import annotations

import logging

from ..state import AgentState

logger = logging.getLogger(__name__)


async def run_sql_executor(state: AgentState) -> AgentState:
    """执行 SQL 查询

    当前 009 模块沙箱执行功能尚未就绪，返回明确的未实现错误。
    待 009 模块完成后，将通过 datasource_id 获取只读连接并执行 SQL。
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

    # 009 模块沙箱执行尚未接入，返回明确的未实现错误
    return {
        **state,
        "execution_result": {
            "columns": [],
            "data_rows": [],
            "row_count": 0,
            "execution_time_ms": 0,
            "error": "SQL 沙箱执行模块尚未接入，请等待 009 模块就绪",
        },
        "error_message": "SQL 沙箱执行模块尚未接入",
        "used_tables": _extract_tables(sql),
        "used_columns": [],
        "current_node": "SQL_EXECUTOR",
    }


def _extract_tables(sql: str) -> list[str]:
    """从 SQL 中简单提取表名（FROM/JOIN 后的标识符）"""
    import re
    tables = set()
    # 匹配 FROM table 和 JOIN table
    for match in re.finditer(r"\b(?:FROM|JOIN)\s+`?(\w+)`?", sql, re.IGNORECASE):
        tables.add(match.group(1))
    return sorted(tables)
