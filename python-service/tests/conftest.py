"""
配置测试进程的共用环境。
"""

import os

# 测试进程不向外部 LangSmith 服务发送追踪数据。
os.environ["LANGCHAIN_TRACING_V2"] = "false"
