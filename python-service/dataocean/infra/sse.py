"""SSE 事件传输层（中性，无业务 schema 依赖）

维护 task_id → asyncio.Queue 映射，提供注册/注销、底层事件入队、事件流消费。
业务侧的类型化事件（ProgressEvent / QueryResult）由 agent.sse 封装后调用本层 emit，
从而让传输机制下沉到 infra，不反向依赖 agent 的业务模型。

SSE 线格式契约（Java consumeSseStream 依赖，不可更改）：
    event: <type>\\ndata: <single-line-json>\\n\\n
"""

from __future__ import annotations

import asyncio
import json
import logging
import time

logger = logging.getLogger(__name__)

# task_id → 事件队列
_event_queues: dict[str, asyncio.Queue] = {}
# task_id → 任务开始时间（供上层计算 elapsed_ms）
_task_start_times: dict[str, float] = {}


def register_task(task_id: str) -> None:
    """注册任务的事件队列"""
    _event_queues[task_id] = asyncio.Queue()
    _task_start_times[task_id] = time.time()


def unregister_task(task_id: str) -> None:
    """清理任务的事件队列"""
    _event_queues.pop(task_id, None)
    _task_start_times.pop(task_id, None)


def task_start_time(task_id: str) -> float:
    """返回任务开始时间，未注册时返回当前时间（供上层计算耗时）"""
    return _task_start_times.get(task_id, time.time())


async def emit(task_id: str, event_type: str, data: dict) -> None:
    """底层事件入队

    Args:
        task_id: 任务 ID
        event_type: 事件类型（progress / result / error）
        data: 已序列化为 dict 的事件数据
    """
    queue = _event_queues.get(task_id)
    if queue is None:
        return
    await queue.put((event_type, data))


async def close_stream(task_id: str) -> None:
    """推送流结束标记，使 event_stream 正常收尾"""
    queue = _event_queues.get(task_id)
    if queue is None:
        return
    await queue.put(None)


async def event_stream(task_id: str):
    """异步生成器，消费事件队列生成 SSE 数据。

    每 15 秒发送一次心跳注释（`: keepalive`），防止代理/负载均衡器因空闲断开连接。
    总超时 120 秒自动终止。
    """
    queue = _event_queues.get(task_id)
    if queue is None:
        return
    keepalive_interval = 15  # 秒
    while True:
        try:
            item = await asyncio.wait_for(queue.get(), timeout=keepalive_interval)
        except asyncio.TimeoutError:
            # 队列无新事件，发送心跳保持连接
            start = _task_start_times.get(task_id, time.time())
            elapsed = time.time() - start
            if elapsed > 120:
                logger.warning("SSE 事件流超时，强制关闭 task_id=%s", task_id)
                break
            yield ": keepalive\n\n"
            continue
        if item is None:
            break
        event_type, data = item
        yield f"event: {event_type}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
