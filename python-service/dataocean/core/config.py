"""统一配置管理

从环境变量读取所有服务配置，使用 pydantic-settings 提供类型安全和默认值。
各模块通过 `from dataocean.core.config import settings` 获取配置。

安全修复：使用版本号+原子切换避免配置热重载竞态。
"""

import threading
from functools import lru_cache

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """应用全局配置"""

    # LLM 配置
    dashscope_api_key: str = ""
    dashscope_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    qwen_model: str = "qwen-plus"
    qwen_embedding_model: str = "text-embedding-v4"
    embedding_api_key: str = ""
    embedding_base_url: str = ""
    embedding_dimension: int = 1024
    llm_timeout: int = 120
    llm_max_retries: int = 2
    llm_temperature: float = 0.3

    # Milvus 向量库
    milvus_host: str = "localhost"
    milvus_port: int = 19530

    # RAG 配置
    rag_top_k: int = 10
    similarity_threshold: float = 0.6
    milvus_collection_name: str = "schema_knowledge"
    embedding_batch_size: int = 25

    # Redis
    redis_host: str = "localhost"
    redis_port: int = 6379

    # LangSmith 可观测性
    langchain_api_key: str = ""
    langchain_tracing_v2: str = "false"
    langchain_project: str = "DataOcean"

    # Agent 工作流配置
    agent_total_timeout: int = 100
    agent_max_retries: int = 3
    agent_node_timeout: int = 30

    # SQL 沙箱配置
    sandbox_max_execution_time: int = 30
    sandbox_max_result_rows: int = 10000
    sandbox_max_subquery_depth: int = 3
    sandbox_pool_max_per_source: int = 10
    sandbox_pool_max_global: int = 50
    sandbox_pool_idle_timeout: int = 1800
    sandbox_pool_wait_timeout: int = 5
    aes_secret_key: str = ""

    # 服务配置
    log_level: str = "INFO"

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8"}


@lru_cache
def get_settings() -> Settings:
    """获取全局配置单例（进程生命周期内只构造一次）"""
    return Settings()


settings = get_settings()

# 配置版本号，用于检测配置变更
_config_version = 0
_config_lock = threading.Lock()


def get_config_version() -> int:
    """获取当前配置版本号"""
    return _config_version


def reload_config(overrides: dict[str, str] | None = None) -> Settings:
    """热重载配置：清除缓存，用 overrides 覆盖环境变量后重建 Settings。

    安全修复：使用版本号+原子切换避免竞态。
    配置变更是低频操作，使用 threading.Lock 保护即可。

    Args:
        overrides: 需要覆盖的配置键值对（来自 Java sys_config）

    Returns:
        新的 Settings 实例
    """
    import os
    global settings, _config_version

    with _config_lock:
        if overrides:
            for key, value in overrides.items():
                env_key = key.upper().replace(".", "_")
                os.environ[env_key] = str(value)

        get_settings.cache_clear()
        new_settings = get_settings()
        # 原子切换：先更新版本号，再更新 settings 引用
        _config_version += 1
        settings = new_settings
        return settings
