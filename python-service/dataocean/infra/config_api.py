"""AI 配置热重载服务

提供从 Java 拉取 AI 配置并热重载的能力。
Java 改配置后回调 /internal/config/reload，触发此模块执行：
1. 从 Java GET /internal/ai-config 拉取最新配置
2. 清除 Settings / ChatOpenAI / Embeddings 缓存
3. 用新配置重建实例
"""

import logging
import os

import httpx

logger = logging.getLogger(__name__)

JAVA_BASE_URL = os.getenv("JAVA_GATEWAY_URL", "http://localhost:8080")
INTERNAL_TOKEN = os.getenv("INTERNAL_TOKEN", "dataocean-internal-default")

# Java config_key -> Python Settings 字段名映射
# 值与 config.py 中 Settings 的字段名一致（pydantic-settings 大小写不敏感，但统一用小写避免混淆）
_CONFIG_KEY_MAP = {
    "ai.dashscope.apiKey": "DASHSCOPE_API_KEY",
    "ai.dashscope.baseUrl": "DASHSCOPE_BASE_URL",
    "ai.llm.model": "QWEN_MODEL",
    "ai.llm.temperature": "LLM_TEMPERATURE",
    "ai.llm.timeout": "LLM_TIMEOUT",
    "ai.llm.maxRetries": "LLM_MAX_RETRIES",
    "ai.embedding.apiKey": "EMBEDDING_API_KEY",
    "ai.embedding.baseUrl": "EMBEDDING_BASE_URL",
    "ai.embedding.model": "QWEN_EMBEDDING_MODEL",
    "ai.embedding.dimension": "EMBEDDING_DIMENSION",
    "ai.embedding.collection": "MILVUS_COLLECTION_NAME",
}


async def reload_ai_config() -> bool:
    """从 Java 拉取最新 AI 配置并热重载。

    Returns:
        True 表示重载成功，False 表示失败
    """
    try:
        # 1. 从 Java 拉取配置
        url = f"{JAVA_BASE_URL}/internal/ai-config"
        headers = {"X-Internal-Token": INTERNAL_TOKEN}
        async with httpx.AsyncClient(timeout=5.0) as client:
            response = await client.get(url, headers=headers)
            response.raise_for_status()
            data = response.json().get("data", {})

        if not data:
            logger.warning("Java 返回的 AI 配置为空")
            return False

        # 2. 转换为 Python env var 格式并覆盖
        overrides = {}
        for java_key, env_key in _CONFIG_KEY_MAP.items():
            value = data.get(java_key, "")
            if value:
                overrides[env_key] = value

        # 3. 重载配置
        from dataocean.core.config import reload_config
        reload_config(overrides)

        # 4. 清除 LLM 和 Embeddings 缓存
        from dataocean.infra.llm import clear_chat_cache
        from dataocean.infra.embeddings import clear_embeddings_cache
        clear_chat_cache()
        clear_embeddings_cache()

        logger.info("AI 配置热重载成功 overrides=%s", list(overrides.keys()))
        return True

    except Exception as e:
        logger.error("AI 配置热重载失败: %s", e)
        return False
