"""进程共用 UTC 日志格式，启动入口负责配置一次。"""

import logging
import sys
import time


def configure_logging(level: str) -> None:
    """日志统一使用 UTC 秒级时间，不记录请求正文或连接凭据。"""
    logging.Formatter.converter = time.gmtime
    logging.basicConfig(
        level=level,
        format="%(asctime)sZ %(levelname)s %(name)s %(message)s",
        datefmt="%Y-%m-%dT%H:%M:%S",
        stream=sys.stdout,
        force=True,
    )
