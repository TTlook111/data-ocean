"""NL2SQL Agent 路由

提供查询执行和任务取消的 HTTP 接口。
- POST /execute — 发起 NL2SQL 查询（LangGraph 工作流）
- POST /tasks/{taskId}/cancel — 取消正在执行的查询
"""

from fastapi import APIRouter, HTTPException

from .cancellation import cancel_task as do_cancel

router = APIRouter()


@router.post("/execute")
async def execute_query() -> dict[str, str]:
    """执行 NL2SQL 查询（待实现）"""
    raise HTTPException(status_code=501, detail="NL2SQL 查询尚未实现")


@router.post("/tasks/{task_id}/cancel")
async def cancel_task(task_id: str) -> dict[str, str]:
    """取消查询任务"""
    do_cancel(task_id)
    return {"status": "cancelled", "taskId": task_id}
