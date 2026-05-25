"""NL2SQL Agent 配置

从全局 settings 和环境变量读取 Agent 工作流相关配置。
"""

from dataocean.core.config import settings


class AgentConfig:
    """Agent 工作流配置"""

    @property
    def qwen_model(self) -> str:
        return settings.qwen_model

    @property
    def qwen_api_key(self) -> str:
        return settings.dashscope_api_key

    @property
    def total_timeout(self) -> int:
        """Agent 总时间预算（秒），默认 100s"""
        return getattr(settings, "agent_total_timeout", 100)

    @property
    def max_retries(self) -> int:
        """SQL 执行失败最大重试次数，默认 3"""
        return getattr(settings, "agent_max_retries", 3)

    @property
    def node_timeout(self) -> int:
        """单节点超时时间（秒），默认 30s"""
        return getattr(settings, "agent_node_timeout", 30)

    @property
    def llm_temperature(self) -> float:
        return settings.llm_temperature

    @property
    def dashscope_base_url(self) -> str:
        return settings.dashscope_base_url


agent_config = AgentConfig()
