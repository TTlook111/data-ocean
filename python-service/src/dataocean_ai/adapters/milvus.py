"""
通过部署配置建立和归还内部向量存储连接。
"""

import os
from collections.abc import Iterator
from contextlib import contextmanager
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    # 类型检查需要 SDK 声明，运行时保留按需加载。
    from pymilvus import MilvusClient

# SDK 导入前关闭自行读取 .env，正式进程使用启动入口注入的配置。
os.environ["PYTHON_DOTENV_DISABLED"] = "1"


@contextmanager
def vector_client(timeout: int) -> Iterator["MilvusClient"]:
    """
    按业务调用的超时取得连接，缺配置由调用入口转换成既有业务错误。
    """
    from pymilvus import MilvusClient

    uri = os.getenv("AI_MILVUS_URI")
    if not uri:
        # 缺少服务时拒绝连接默认存储。
        raise OSError("Milvus storage is not configured")
    client = MilvusClient(uri=uri, token=os.getenv("AI_MILVUS_TOKEN", ""), timeout=timeout)
    try:
        # 成功构建的客户端仅在本次操作中使用。
        yield client
    finally:
        # 查询或写入失败时也归还连接。
        client.close()
