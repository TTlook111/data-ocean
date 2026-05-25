"""SQL 沙箱执行器

在只读事务中执行已校验的 SQL，强制超时控制和结果集限制。
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field

from .config import sandbox_config
from . import pool_manager

logger = logging.getLogger(__name__)


@dataclass
class ExecutionResult:
    """SQL 执行结果"""

    success: bool = True
    rows: list[dict] = field(default_factory=list)
    columns: list[dict] = field(default_factory=list)
    row_count: int = 0
    execution_time_ms: int = 0
    error: str = ""
    truncated: bool = False
    masked_fields: list[str] = field(default_factory=list)


async def execute(
    sql: str,
    datasource_id: int,
    connection_config: dict,
    mask_columns: list[str] | None = None,
) -> ExecutionResult:
    """在沙箱中执行 SQL

    Args:
        sql: 已校验和改写的 SQL
        datasource_id: 数据源 ID
        connection_config: 连接配置
        mask_columns: 需脱敏的字段列表

    Returns:
        ExecutionResult 包含数据行、列信息和执行时间
    """
    start = time.time()

    try:
        engine = pool_manager.get_engine(datasource_id, connection_config)
    except RuntimeError as e:
        return ExecutionResult(success=False, error=str(e))
    except Exception as e:
        logger.error("获取连接池失败 datasource_id=%d error=%s", datasource_id, e)
        return ExecutionResult(success=False, error="数据库连接失败")

    try:
        result = await asyncio.wait_for(
            asyncio.to_thread(_execute_readonly, engine, sql),
            timeout=sandbox_config.max_execution_time,
        )
        result.execution_time_ms = int((time.time() - start) * 1000)
        result.masked_fields = mask_columns or []
        return result
    except asyncio.TimeoutError:
        elapsed = int((time.time() - start) * 1000)
        logger.warning("SQL 执行超时 datasource_id=%d elapsed=%dms sql=%s",
                       datasource_id, elapsed, sql[:80])
        return ExecutionResult(
            success=False,
            error=f"查询超时（{sandbox_config.max_execution_time}s），已自动终止",
            execution_time_ms=elapsed,
        )
    except Exception as e:
        elapsed = int((time.time() - start) * 1000)
        logger.error("SQL 执行异常 datasource_id=%d error=%s", datasource_id, e)
        return ExecutionResult(success=False, error=str(e), execution_time_ms=elapsed)


def _execute_readonly(engine, sql: str) -> ExecutionResult:
    """在只读事务中同步执行 SQL"""
    from sqlalchemy import text

    max_rows = sandbox_config.max_result_rows

    with engine.connect() as conn:
        # 设置只读事务和超时
        conn.execute(text("SET TRANSACTION READ ONLY"))
        conn.execute(text(f"SET max_execution_time = {sandbox_config.max_execution_time * 1000}"))

        # SQL 已经过 validator AST 校验 + 注入模式检测，此处直接执行是安全的
        result = conn.execute(text(sql))
        columns = [
            {"name": col, "type": str(result.cursor.description[i][1]) if result.cursor.description else ""}
            for i, col in enumerate(result.keys())
        ]

        rows = []
        truncated = False
        for i, row in enumerate(result.mappings()):
            if i >= max_rows:
                truncated = True
                break
            rows.append(dict(row))

        conn.rollback()

    return ExecutionResult(
        success=True,
        rows=rows,
        columns=columns,
        row_count=len(rows),
        truncated=truncated,
    )
