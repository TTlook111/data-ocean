"""NL2SQL Agent 路由

提供查询执行和任务取消的 HTTP 接口。
- POST /execute — 发起 NL2SQL 查询（SSE 流式返回）
- POST /tasks/{taskId}/cancel — 取消正在执行的查询
- GET /health — Agent 服务健康检查
"""

from __future__ import annotations

import asyncio

from dataocean.core.error_messages import sanitize_error
import logging
import time

from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse

from dataocean.infra.cancellation import cancel_task as do_cancel, cleanup
from .config import agent_config
from .graph import agent_graph
from .schema import ExecuteRequest, QueryResult
from . import sse
from dataocean.infra.timeout_budget import TimeoutBudget

logger = logging.getLogger(__name__)

router = APIRouter()

# 活跃的后台 Agent 任务引用，防止被 GC 回收，并支持取消时终止
_active_tasks: dict[str, asyncio.Task] = {}


@router.post("/execute")
async def execute_query(request: ExecuteRequest) -> StreamingResponse:
    """执行 NL2SQL 查询，返回 SSE 事件流"""
    task_id = request.task_id
    logger.info("收到查询请求 task_id=%s question=%s", task_id, request.question[:50])

    # 注册 SSE 事件队列
    sse.register_task(task_id)

    # 在后台启动 Agent 工作流并保存引用
    task = asyncio.create_task(_run_agent(task_id, request))
    _active_tasks[task_id] = task
    task.add_done_callback(lambda _: _active_tasks.pop(task_id, None))

    # 返回 SSE 流
    return StreamingResponse(
        sse.event_stream(task_id),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


async def _run_agent(task_id: str, request: ExecuteRequest) -> None:
    """后台执行 Agent 工作流并推送结果"""
    start_time = time.time()
    try:
        # 构建初始状态
        initial_state = {
            "task_id": task_id,
            "question": request.question,
            "datasource_id": request.datasource_id,
            "user_id": request.user_id,
            "conversation_history": [t.model_dump() for t in request.conversation_history],
            "user_permissions": request.user_permissions.model_dump(),
            "active_snapshot_id": request.active_snapshot_id,
            "confidence_scores": request.confidence_scores,
            "glossary_terms": [t for t in (request.glossary_terms or [])],
            "prompt_versions": [],
            "connection_config": request.connection_config,
            "fallback_chunks": request.fallback_chunks,
            "rewritten_query": "",
            "extracted_intent": {},
            "schema_context": [],
            "generated_sql": "",
            "sql_explanation": "",
            "validation_result": {},
            "execution_result": {},
            "used_tables": [],
            "used_columns": [],
            "chart_config": None,
            "current_node": "",
            "retry_count": 0,
            "error_message": "",
            "errors": [],
            "start_time": start_time,
            "cancelled": False,
            "timeout_budget": TimeoutBudget(float(agent_config.total_timeout)),
        }

        # 执行 LangGraph 工作流
        final_state = await agent_graph.ainvoke(initial_state)

        # 组装最终结果
        total_time_ms = int((time.time() - start_time) * 1000)
        error_msg = final_state.get("error_message", "")
        execution = final_state.get("execution_result", {})

        if error_msg:
            result = QueryResult(
                task_id=task_id,
                status="FAILED",
                error=error_msg,
                retry_count=final_state.get("retry_count", 0),
                total_time_ms=total_time_ms,
                rewritten_query=final_state.get("rewritten_query"),
                prompt_versions=final_state.get("prompt_versions", []),
                degraded=final_state.get("degraded", False),
                degrade_notice=final_state.get("degrade_notice"),
            )
        else:
            # 返回经过权限改写后的 SQL（如有），否则返回原始生成的 SQL
            validation_result = final_state.get("validation_result", {})
            final_sql = validation_result.get("rewritten_sql") or final_state.get("generated_sql")

            result = QueryResult(
                task_id=task_id,
                status="COMPLETED",
                sql=final_sql,
                sql_explanation=final_state.get("sql_explanation"),
                data=execution.get("data_rows"),
                columns=[{"name": c.get("name", ""), "type": c.get("type", ""), "comment": c.get("comment")}
                         for c in execution.get("columns", [])],
                row_count=execution.get("row_count", 0),
                chart_config=final_state.get("chart_config"),
                used_tables=final_state.get("used_tables", []),
                used_columns=final_state.get("used_columns", []),
                rewritten_query=final_state.get("rewritten_query"),
                retry_count=final_state.get("retry_count", 0),
                total_time_ms=total_time_ms,
                suggested_questions=final_state.get("suggested_questions", []),
                masked_fields=validation_result.get("masked_fields", {}),
                prompt_versions=final_state.get("prompt_versions", []),
                degraded=final_state.get("degraded", False),
                degrade_notice=final_state.get("degrade_notice"),
            )

        await sse.emit_result(task_id, result)

    except asyncio.CancelledError:
        # 用户取消或 SSE 断开导致 Task 被 cancel
        logger.info("Agent 工作流被取消 task_id=%s", task_id)
        total_time_ms = int((time.time() - start_time) * 1000)
        result = QueryResult(
            task_id=task_id,
            status="CANCELLED",
            error="查询已取消",
            total_time_ms=total_time_ms,
        )
        await sse.emit_result(task_id, result)
    except Exception as e:
        logger.error("Agent 工作流执行异常 task_id=%s", task_id, exc_info=True)
        total_time_ms = int((time.time() - start_time) * 1000)
        result = QueryResult(
            task_id=task_id,
            status="FAILED",
            error=sanitize_error(e),
            total_time_ms=total_time_ms,
        )
        await sse.emit_result(task_id, result)
    finally:
        cleanup(task_id)
        sse.unregister_task(task_id)


@router.post("/tasks/{task_id}/cancel")
async def cancel_task(task_id: str) -> dict:
    """取消查询任务"""
    do_cancel(task_id)
    # 同时取消 asyncio Task，使正在执行的 SQL 能被中断
    active_task = _active_tasks.get(task_id)
    if active_task and not active_task.done():
        active_task.cancel()
    return {"taskId": task_id, "cancelled": True, "message": "任务已标记取消"}


@router.get("/health")
async def health() -> dict:
    """Agent 服务健康检查"""
    return {
        "status": "healthy",
        "llmAvailable": True,
        "ragAvailable": True,
    }
