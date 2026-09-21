"""IAM-SIMPLE-1 独立内部路由。"""

from __future__ import annotations

import json

from fastapi import APIRouter
from fastapi.responses import StreamingResponse

from dataocean.agent import sse

from .schema import S1QueryExecuteRequest, S1RagRetrieveRequest, S1SqlExecuteRequest, S1SqlValidateRequest
from .service import execute_validated, retrieve, run_query, validate_request
from dataocean.infra.cancellation import cancel_task as do_cancel

router = APIRouter()


def _event(name: str, data: object) -> str:
    return f"event: {name}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


@router.post("/query/execute")
async def execute_query(request: S1QueryExecuteRequest) -> StreamingResponse:
    async def stream():
        yield _event("progress", {"taskId": request.taskId, "protocolVersion": request.protocolVersion, "permissionRevision": request.permissionRevision, "node": "S1_FIREWALL", "status": "completed"})
        result = await run_query(request)
        yield _event("result", result)

    return StreamingResponse(stream(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@router.post("/sql/validate")
async def validate_sql(request: S1SqlValidateRequest) -> dict:
    result = validate_request(request)
    return {
        "taskId": request.taskId,
        "protocolVersion": request.protocolVersion,
        "permissionRevision": request.permissionRevision,
        "passed": result.passed,
        "rewrittenSql": result.sql if result.passed else None,
        "violations": result.violations,
        "usedTables": result.used_tables,
        "usedColumns": result.used_columns,
        "sourceTrace": result.source_trace,
        "maskedFields": result.masked_fields,
    }


@router.post("/sql/execute")
async def execute_sql(request: S1SqlExecuteRequest) -> dict:
    return await execute_validated(request)


@router.post("/rag/retrieve")
async def retrieve_rag(request: S1RagRetrieveRequest) -> dict:
    return {"taskId": request.taskId, "protocolVersion": request.protocolVersion, "chunks": await retrieve(request)}


@router.post("/query/tasks/{task_id}/cancel")
async def cancel_query(task_id: str) -> dict:
    do_cancel(task_id)
    return {"taskId": task_id, "protocolVersion": "IAM-SIMPLE-1", "cancelled": True}
