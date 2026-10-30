"""SQL 沙箱连接池路由

B6 批次 3：`POST /validate` 与 `POST /execute` 两个旧契约端点已删除。
它们依赖旧的平铺权限字段（`allowed_tables` / `row_filters` / `denied_columns` /
`mask_columns`），唯一调用方是 Java 的 `PythonAgentClientImpl`，已随旧问数链路一并删除。

SQL 校验与执行现在走 IAM-SIMPLE-1 的 `/internal/iam-s1/sql/validate` 与
`/internal/iam-s1/sql/execute`，使用独立契约（`permissionSnapshot` + `executionBindings`），
实现在 `iam_s1/sql_security.py` 与 `iam_s1/service.py`。

本路由只保留 Java 仍在调用的连接池管理端点（`PythonPoolClientImpl`）。
"""

from __future__ import annotations

import asyncio
import logging

from fastapi import APIRouter

from .pool_manager import destroy_pool, get_pool_status

logger = logging.getLogger(__name__)

router = APIRouter()


@router.delete("/pools/{datasource_id}")
async def delete_pool(datasource_id: int) -> dict:
    """销毁指定数据源的连接池"""
    await asyncio.to_thread(destroy_pool, datasource_id)
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
    await asyncio.to_thread(destroy_pool, datasource_id)
    return {"datasourceId": datasource_id, "reset": True, "message": "连接池已销毁，下次查询时自动重建"}
