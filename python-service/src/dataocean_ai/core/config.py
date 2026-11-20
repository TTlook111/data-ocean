"""从当前进程读取已声明的运行参数，不隐式加载仓库根目录的 Compose 环境。"""

import os
from pathlib import Path
from typing import Literal, Self

from pydantic import BaseModel, ConfigDict, Field, SecretStr


def env(name: str, previous: str, default: str = "") -> str:
    """优先读取短变量名，已有部署仍可沿用旧配置。"""
    return os.getenv(name, os.getenv(previous, default))


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

    # 控制日志交给标准输出还是标准错误，由宿主机负责收集。
    log_output: Literal["stdout", "stderr"] = "stdout"

    @classmethod
    def from_environment(cls) -> Self:
        """只读取本应用前缀，避免另一端的环境变量改变当前进程行为。"""
        return cls.model_validate(
            {
                "host": env("AI_HOST", "DATAOCEAN_AI_HOST", "127.0.0.1"),
                "port": env("AI_PORT", "DATAOCEAN_AI_PORT", "18090"),
                "log_level": env("AI_LOG_LEVEL", "DATAOCEAN_AI_LOG_LEVEL", "INFO").upper(),
                "log_output": env("AI_LOG_OUTPUT", "DATAOCEAN_AI_LOG_OUTPUT", "stdout"),
            }
        )


class GovernanceSettings(BaseModel):
    """集中读取内部调用、凭据文件和任务队列的启动参数。"""

    # 错误不回显 Redis URL 等运行参数。
    model_config = ConfigDict(frozen=True, hide_input_in_errors=True)

    # 当前部署的任务队列连接，可能包含认证信息。
    redis_url: SecretStr | None = None

    # 隔离同一 Redis 实例的不同部署。
    namespace: str = "dataocean:governance"

    # Worker 回查任务资格的 Java 内部基址。
    java_url: str | None = None

    # Python API 使用此公钥验证 Java 的短期令牌。
    java_public_key: Path | None = None

    # Worker 使用此私钥签发回查 Java 的短期令牌。
    worker_private_key: Path | None = None

    @classmethod
    def from_environment(cls) -> Self:
        """读取正式业务配置，保留独立健康入口的最小部署方式。"""
        return cls(
            redis_url=env("AI_QUEUE_URL", "DATAOCEAN_AI_GOVERNANCE_REDIS_URL") or None,
            namespace=env(
                "AI_QUEUE_PREFIX", "DATAOCEAN_AI_GOVERNANCE_NAMESPACE", "dataocean:governance"
            ),
            java_url=env("AI_JAVA_URL", "DATAOCEAN_AI_JAVA_URL") or None,
            java_public_key=env("AI_JAVA_PUBLIC_KEY", "DATAOCEAN_AI_JAVA_PUBLIC_KEY_FILE") or None,
            worker_private_key=env("AI_WORKER_PRIVATE_KEY", "DATAOCEAN_AI_WORKER_PRIVATE_KEY_FILE")
            or None,
        )

    def validate_startup(self, *, worker: bool) -> None:
        """在启动边界明确报告必要配置缺项，避免任务进入半配置进程。"""
        if not worker and self.redis_url is None and self.java_public_key is None:
            # 单独使用基础 API 时，业务端点仍按已有边界拒绝调用。
            return
        required = {"AI_QUEUE_URL": self.redis_url}
        if worker:
            # Worker 消费任务及访问 Java，API 只验证传入身份。
            required["AI_JAVA_URL"] = self.java_url
            required["AI_WORKER_PRIVATE_KEY"] = (
                self.worker_private_key and self.worker_private_key.is_file()
            )
        else:
            # 服务公钥必须可定位到实际文件。
            required["AI_JAVA_PUBLIC_KEY"] = self.java_public_key and self.java_public_key.is_file()
        missing = [name for name, value in required.items() if not value]
        if missing:
            # 只提示参数名，配置值留在私有文件中。
            raise SystemExit("治理服务配置缺失或文件不存在：" + "、".join(missing))
