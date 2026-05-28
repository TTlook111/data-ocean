"""取消令牌管理（resilience 层封装）

对 dataocean.agent.cancellation 的增强封装，
提供面向 resilience 模块的统一取消接口。

实际取消逻辑仍由 agent.cancellation 模块管理（单实例部署）。
"""

from dataocean.agent.cancellation import (
    cancel_task,
    is_cancelled,
    cleanup,
)

__all__ = ["cancel_task", "is_cancelled", "cleanup"]
