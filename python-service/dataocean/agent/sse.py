"""SSE 事件推送管理

维护 task_id → asyncio.Queue 映射，
各节点通过 emit_progress 推送进度事件，
路由层通过 event_stream 消费队列生成 SSE 流。
"""

from __future__ import annotations

import asyncio
import json
import logging
import time

from .schema import ProgressEvent, QueryResult

logger = logging.getLogger(__name__)

_event_queues: dict[str, asyncio.Queue] = {}
_task_start_times: dict[str, float] = {}


def register_task(task_id: str) -> None:
    """注册任务的事件队列"""
    _event_queues[task_id] = asyncio.Queue()
    _task_start_times[task_id] = time.time()


def unregister_task(task_id: str) -> None:
    """清理任务的事件队列"""
    _event_queues.pop(task_id, None)
    _task_start_times.pop(task_id, None)


async def emit_progress(
    task_id: str,
    node: str,
    status: str,
    message: str,
    retry_count: int = 0,
) -> None:
    """推送进度事件到队列"""
    queue = _event_queues.get(task_id)
    if queue is None:
        return
    start = _task_start_times.get(task_id, time.time())
    elapsed_ms = int((time.time() - start) * 1000)
    event = ProgressEvent(
        task_id=task_id,
        node=node,
        status=status,
        message=message,
        retry_count=retry_count,
        elapsed_ms=elapsed_ms,
    )
    await queue.put(("progress", event.model_dump(by_alias=True)))


async def emit_result(task_id: str, result: QueryResult) -> None:
    """推送最终结果事件"""
    queue = _event_queues.get(task_id)
    if queue is None:
        return
    event_type = "error" if result.status != "COMPLETED" else "result"
    await queue.put((event_type, result.model_dump(by_alias=True)))
    await queue.put(None)


async def event_stream(task_id: str):
    """异步生成器，消费事件队列生成 SSE 数据，超时 120 秒自动终止"""
    queue = _event_queues.get(task_id)
    if queue is None:
        return
    while True:
        try:
            item = await asyncio.wait_for(queue.get(), timeout=120)
        except asyncio.TimeoutError:
            logger.warning("SSE 事件流超时，强制关闭 task_id=%s", task_id)
            break
        if item is None:
            break
        event_type, data = item
        yield f"event: {event_type}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
