"""SSE 类型化事件层（agent 业务层）

在 infra.sse 传输层之上提供类型化的事件推送接口：
- emit_progress: 推送 ProgressEvent（节点进度）
- emit_result:   推送 QueryResult（最终结果，区分 result / error）

传输机制（队列、线格式、心跳）由 infra.sse 负责，本模块只做「业务模型 → dict」的序列化，
从而让 agent 依赖 infra（方向向下），而非把 schema 依赖塞进中性层。

对外保留原有函数名与签名，graph.py / router.py 的 `sse.xxx` 调用无需改动。
"""

from __future__ import annotations

import time

from dataocean.infra import sse as transport

from .schema import ProgressEvent, QueryResult

# 透传传输层的注册/注销/事件流，保持原 agent.sse 的公开接口不变
register_task = transport.register_task
unregister_task = transport.unregister_task
event_stream = transport.event_stream


async def emit_progress(
    task_id: str,
    node: str,
    status: str,
    message: str,
    retry_count: int = 0,
) -> None:
    """推送进度事件到队列"""
    start = transport.task_start_time(task_id)
    elapsed_ms = int((time.time() - start) * 1000)
    event = ProgressEvent(
        task_id=task_id,
        node=node,
        status=status,
        message=message,
        retry_count=retry_count,
        elapsed_ms=elapsed_ms,
    )
    await transport.emit(task_id, "progress", event.model_dump(by_alias=True))


async def emit_result(task_id: str, result: QueryResult) -> None:
    """推送最终结果事件"""
    event_type = "error" if result.status != "COMPLETED" else "result"
    await transport.emit(task_id, event_type, result.model_dump(by_alias=True))
    await transport.close_stream(task_id)
