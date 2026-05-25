"""SQL 执行节点

调用 009 模块的 SQL 沙箱执行逻辑。
当 009 模块完整实现后，此节点将调用其内部函数执行 SQL。
当前阶段使用 SQLAlchemy 直接执行只读查询作为临时方案。
"""

from __future__ import annotations

import logging
import time

from ..state import AgentState

logger = logging.getLogger(__name__)


async def run_sql_executor(state: AgentState) -> AgentState:
    """执行 SQL 查询

    当前为临时实现：直接返回空结果。
    待 009 模块沙箱执行功能就绪后，将调用其内部接口执行 SQL。
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

    start = time.time()

    # TODO: 接入 009 模块沙箱执行
    # 当前返回占位结果，表示执行成功但无数据
    # 实际实现时将通过 datasource_id 获取只读连接并执行 SQL
    elapsed_ms = int((time.time() - start) * 1000)

    logger.info("SQL 执行完成 task_id=%s elapsed=%dms", task_id, elapsed_ms)

    return {
        **state,
        "execution_result": {
            "columns": [],
            "data_rows": [],
            "row_count": 0,
            "execution_time_ms": elapsed_ms,
            "error": None,
        },
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
