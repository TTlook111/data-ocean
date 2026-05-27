"""SQL 安全沙箱路由

提供 SQL 安全校验和沙箱执行的 HTTP 接口。
- POST /validate — SQL AST 安全校验 + 改写
- POST /execute — SQL 沙箱执行（只读连接，强制 LIMIT，超时 30s）
- GET /health — 健康检查（含连接池状态）
- DELETE /pools/{datasourceId} — 销毁指定数据源连接池
"""

from __future__ import annotations

import logging

from fastapi import APIRouter

from .validator import validate
from .rewriter import rewrite
from .executor import execute as execute_sql
from .pool_manager import destroy_pool, get_pool_status
from .schema import ValidateRequest, ValidateResponse, ExecuteRequest, ExecuteResponse

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post("/validate", response_model=ValidateResponse)
async def validate_sql_endpoint(request: ValidateRequest) -> ValidateResponse:
    """SQL 安全校验 + AST 改写"""
    sql = request.sql.strip()
    logger.info("SQL 校验请求 datasource_id=%d sql=%s", request.datasource_id, sql[:80])

    # 第一步：AST 安全校验
    validation = validate(sql, request.allowed_tables or None)
    if not validation.passed:
        return ValidateResponse(
            passed=False,
            violations=validation.reasons,
        )

    # 第二步：AST 改写（注入行过滤、列检查、LIMIT）
    row_filters_dict: dict[str, list[str]] = {}
    for rf in request.row_filters:
        row_filters_dict.setdefault(rf.table_name, []).append(rf.condition)

    rewrite_result = rewrite(
        sql=sql,
        row_filters=row_filters_dict or None,
        denied_columns=request.denied_columns or None,
        mask_columns=request.mask_columns or None,
        mask_strategies=request.mask_strategies or None,
    )

    if not rewrite_result.success:
        return ValidateResponse(
            passed=False,
            violations=[rewrite_result.denied_reason],
        )

    return ValidateResponse(
        passed=True,
        rewritten_sql=rewrite_result.rewritten_sql,
        masked_columns=rewrite_result.masked_fields,
    )


@router.post("/execute", response_model=ExecuteResponse)
async def execute_sql_endpoint(request: ExecuteRequest) -> ExecuteResponse:
    """SQL 沙箱执行"""
    logger.info("SQL 执行请求 datasource_id=%d sql=%s", request.datasource_id, request.sql[:80])

    result = await execute_sql(
        sql=request.sql,
        datasource_id=request.datasource_id,
        connection_config=request.connection_config.model_dump(),
        mask_columns=request.mask_columns,
    )

    return ExecuteResponse(
        success=result.success,
        data=result.rows if result.success else None,
        columns=result.columns if result.success else None,
        row_count=result.row_count,
        execution_time_ms=result.execution_time_ms,
        error=result.error or None,
        error_type=result.error_type or None,
        truncated=result.truncated,
        masked_columns=result.masked_fields,
    )


@router.delete("/pools/{datasource_id}")
async def delete_pool(datasource_id: int) -> dict:
    """销毁指定数据源的连接池"""
    destroy_pool(datasource_id)
    return {"datasourceId": datasource_id, "destroyed": True}


@router.get("/health")
async def health() -> dict:
    """健康检查，返回连接池状态"""
    pools = get_pool_status()
    return {
        "status": "healthy",
        "activePools": len(pools),
        "pools": pools,
    }


@router.get("/pools/dashboard")
async def pools_dashboard() -> dict:
    """连接池详细状态面板"""
    pools = get_pool_status()
    return {
        "activePools": len(pools),
        "pools": pools,
    }


@router.post("/pools/{datasource_id}/reset")
async def reset_pool(datasource_id: int) -> dict:
    """强制销毁并重建指定数据源的连接池（重建在下次请求时自动触发）"""
    destroy_pool(datasource_id)
    return {"datasourceId": datasource_id, "reset": True, "message": "连接池已销毁，下次查询时自动重建"}
