"""查询取消令牌管理

单实例部署下使用内存字典管理 CancellationToken，
支持查询执行过程中的即时取消。
"""

import logging
from collections.abc import Set

logger = logging.getLogger(__name__)

_cancelled_tasks: set[str] = set()


def cancel(task_id: str) -> None:
    """标记任务为已取消"""
    _cancelled_tasks.add(task_id)
    logger.info("任务已标记取消 task_id=%s", task_id)


def is_cancelled(task_id: str) -> bool:
    """检查任务是否已被取消"""
    return task_id in _cancelled_tasks


def cleanup(task_id: str) -> None:
    """任务完成后清理取消标记"""
    _cancelled_tasks.discard(task_id)
