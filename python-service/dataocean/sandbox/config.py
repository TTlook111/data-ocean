"""SQL 安全沙箱配置

从全局 settings 读取安全相关配置参数。
"""

from dataocean.core.config import settings


class SandboxConfig:
    """沙箱安全配置"""

    @property
    def max_execution_time(self) -> int:
        """SQL 最大执行时间（秒），默认 30s"""
        return getattr(settings, "sandbox_max_execution_time", 30)

    @property
    def max_result_rows(self) -> int:
        """最大返回行数，默认 10000"""
        return getattr(settings, "sandbox_max_result_rows", 10000)

    @property
    def max_subquery_depth(self) -> int:
        """最大子查询嵌套深度，默认 3"""
        return getattr(settings, "sandbox_max_subquery_depth", 3)

    @property
    def pool_max_per_source(self) -> int:
        """单数据源最大连接数，默认 10"""
        return getattr(settings, "sandbox_pool_max_per_source", 10)

    @property
    def pool_max_global(self) -> int:
        """全局最大连接数，默认 50"""
        return getattr(settings, "sandbox_pool_max_global", 50)

    @property
    def pool_idle_timeout(self) -> int:
        """连接池空闲超时（秒），默认 1800"""
        return getattr(settings, "sandbox_pool_idle_timeout", 1800)

    @property
    def pool_wait_timeout(self) -> int:
        """连接池等待超时（秒），默认 5"""
        return getattr(settings, "sandbox_pool_wait_timeout", 5)

    @property
    def aes_secret_key(self) -> str:
        """AES-256 密钥（用于解密数据源密码）"""
        return getattr(settings, "aes_secret_key", "")


sandbox_config = SandboxConfig()
