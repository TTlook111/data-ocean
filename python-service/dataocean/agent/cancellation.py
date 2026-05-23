"""查询取消令牌管理

单实例部署下使用内存字典管理 CancellationToken，
支持查询执行过程中的即时取消。
带 TTL 自动清理机制，防止内存泄漏。
"""

import logging
import time

logger = logging.getLogger(__name__)

_TASK_TTL_SECONDS = 3600

_cancelled_tasks: dict[str, float] = {}


def cancel_task(task_id: str) -> None:
    """标记任务为已取消"""
    _evict_expired()
    _cancelled_tasks[task_id] = time.monotonic()
    logger.info("任务已标记取消 task_id=%s", task_id)


def is_cancelled(task_id: str) -> bool:
    """检查任务是否已被取消"""
    ts = _cancelled_tasks.get(task_id)
    if ts is None:
        return False
    if time.monotonic() - ts > _TASK_TTL_SECONDS:
        _cancelled_tasks.pop(task_id, None)
        return False
    return True


def cleanup(task_id: str) -> None:
    """任务完成后清理取消标记"""
    _cancelled_tasks.pop(task_id, None)


def _evict_expired() -> None:
    """清理过期的取消标记"""
    now = time.monotonic()
    expired = [k for k, ts in _cancelled_tasks.items() if now - ts > _TASK_TTL_SECONDS]
    for k in expired:
        _cancelled_tasks.pop(k, None)
