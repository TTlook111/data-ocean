"""NL2SQL Agent 路由

提供查询执行和任务取消的 HTTP 接口。
- POST /execute — 发起 NL2SQL 查询（LangGraph 工作流）
- POST /tasks/{taskId}/cancel — 取消正在执行的查询
"""

from fastapi import APIRouter

router = APIRouter()


@router.post("/execute")
async def execute_query() -> dict[str, str]:
    """执行 NL2SQL 查询（待实现）"""
    return {"status": "not_implemented"}


@router.delete("/tasks/{task_id}/cancel")
async def cancel_task(task_id: str) -> dict[str, str]:
    """取消查询任务（待实现）"""
    return {"status": "not_implemented", "taskId": task_id}
