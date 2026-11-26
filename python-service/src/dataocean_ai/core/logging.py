"""进程共用 UTC 日志格式，启动入口负责配置一次。"""

import logging
import sys
import time


def configure_logging(level: str, output: str = "stdout") -> None:
    """统一使用 UTC 秒级时间，按启动配置选定宿主机输出流。"""
    logging.Formatter.converter = time.gmtime
    logging.basicConfig(
        level=level,
        format="%(asctime)sZ %(levelname)s %(name)s %(message)s",
        datefmt="%Y-%m-%dT%H:%M:%S",
        stream=sys.stderr if output == "stderr" else sys.stdout,
        force=True,
    )
