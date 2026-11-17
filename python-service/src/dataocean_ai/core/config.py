"""从当前进程读取已声明的运行参数，不隐式加载仓库根目录的 Compose 环境。"""

import os
from typing import Literal, Self

from pydantic import BaseModel, ConfigDict, Field


class Settings(BaseModel):
    """启动前完成边界校验，错误配置直接阻止进程启动。"""

    # 参数在启动时固定，运行期间不偷偷改变进程监听或日志等级。
    model_config = ConfigDict(extra="forbid", frozen=True)

    # 内部 API 的宿主机监听地址，开发默认只接受本机连接。
    host: str = Field(default="127.0.0.1", min_length=1)

    # 与 Java、前端及共享依赖分开的内部 API 端口。
    port: int = Field(default=18090, ge=1, le=65535)

    # 使用标准日志等级，不将秘密请求内容作为诊断输出。
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"] = "INFO"

    @classmethod
    def from_environment(cls) -> Self:
        """只读取本应用前缀，避免另一端的环境变量改变当前进程行为。"""
        return cls.model_validate(
            {
                "host": os.getenv("DATAOCEAN_AI_HOST", "127.0.0.1"),
                "port": os.getenv("DATAOCEAN_AI_PORT", "18090"),
                "log_level": os.getenv("DATAOCEAN_AI_LOG_LEVEL", "INFO").upper(),
            }
        )
