"""统一日志配置

为所有模块提供一致的日志格式和级别控制。
"""

import logging
import sys


def setup_logging(level: str = "INFO") -> None:
    """初始化日志配置

    Args:
        level: 日志级别（DEBUG/INFO/WARNING/ERROR）
    """
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s | %(levelname)-7s | %(name)s | %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
        stream=sys.stdout,
        force=True,
    )
    # 降低第三方库的日志级别
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("uvicorn.access").setLevel(logging.WARNING)
