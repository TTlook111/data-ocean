"""统一配置管理

从环境变量读取所有服务配置，使用 pydantic-settings 提供类型安全和默认值。
各模块通过 `from dataocean.core.config import settings` 获取配置。
"""

from functools import lru_cache

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """应用全局配置"""

    # LLM 配置
    dashscope_api_key: str = ""
    qwen_model: str = "qwen-plus"
    qwen_embedding_model: str = "text-embedding-v4"
    embedding_dimension: int = 1024

    # Milvus 向量库
    milvus_host: str = "localhost"
    milvus_port: int = 19530

    # Redis
    redis_host: str = "localhost"
    redis_port: int = 6379

    # LangSmith 可观测性
    langchain_api_key: str = ""
    langchain_tracing_v2: str = "false"
    langchain_project: str = "DataOcean"

    # 服务配置
    log_level: str = "INFO"

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8"}


@lru_cache
def get_settings() -> Settings:
    """获取全局配置单例"""
    return Settings()


settings = get_settings()
