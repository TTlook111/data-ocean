"""LLM 调用层（基于 LangChain ChatOpenAI）

统一封装对 Qwen（DashScope OpenAI 兼容端点）的调用，供所有业务模块共享：
agent 节点、chart 服务、knowledge 草稿生成均走此处，消除各自手写的 httpx + 重试代码。

属于 infra 中性层，只依赖 core.config，不依赖任何业务模块。
保持 call_llm 的函数签名与原 agent.llm.call_llm 一致，调用方无需改动。
"""

from __future__ import annotations

import logging

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI

from dataocean.core.config import get_settings
from dataocean.core.exceptions import LLMException

logger = logging.getLogger(__name__)

# 缓存不同 (model, temperature) 组合的 ChatOpenAI 实例，避免重复构造
# 注：在纯 asyncio 单线程事件循环中，dict 操作（无 await 的同步代码段）是安全的，
# 因为协程切换只发生在 await 点。CPython GIL 保证 dict 操作的线程安全。
_chat_cache: dict[tuple[str, str, float], ChatOpenAI] = {}


def clear_chat_cache() -> None:
    """清除所有缓存的 ChatOpenAI 实例（配置热重载时调用）"""
    _chat_cache.clear()
    logger.info("LLM ChatOpenAI 缓存已清除")


def get_chat_model(
    *,
    model: str | None = None,
    temperature: float | None = None,
    max_retries: int | None = None,
) -> ChatOpenAI:
    """获取（缓存的）ChatOpenAI 实例，指向 DashScope OpenAI 兼容端点。

    Args:
        model: 模型名，默认 settings.qwen_model
        temperature: 温度，默认 settings.llm_temperature
        max_retries: 最大重试次数，默认 settings.llm_max_retries

    Returns:
        配置好的 ChatOpenAI 实例
    """
    settings = get_settings()
    model = model or settings.qwen_model
    temperature = temperature if temperature is not None else settings.llm_temperature
    retries = max_retries if max_retries is not None else settings.llm_max_retries

    cache_key = (settings.dashscope_base_url, model, temperature)

    # 检查缓存
    cached = _chat_cache.get(cache_key)
    if cached is not None:
        return cached

    # 创建新实例
    chat = ChatOpenAI(
        model=model,
        temperature=temperature,
        api_key=settings.dashscope_api_key or "dummy",
        base_url=settings.dashscope_base_url,
        timeout=float(settings.llm_timeout),
        max_retries=retries,
    )
    _chat_cache[cache_key] = chat
    return chat


async def call_llm(
    system_prompt: str,
    user_prompt: str,
    *,
    model: str | None = None,
    temperature: float | None = None,
    max_retries: int = 2,
) -> str:
    """调用 Qwen 获取文本响应（兼容原 agent.llm.call_llm 签名）。

    Args:
        system_prompt: 系统角色提示
        user_prompt: 用户消息
        model: 模型名称，默认 settings.qwen_model
        temperature: 温度参数，默认 settings.llm_temperature
        max_retries: 最大重试次数（由 ChatOpenAI 内部执行）

    Returns:
        LLM 生成的文本内容

    Raises:
        LLMException: 调用失败或响应为空时抛出
    """
    chat = get_chat_model(model=model, temperature=temperature, max_retries=max_retries)
    messages = [
        SystemMessage(content=system_prompt),
        HumanMessage(content=user_prompt),
    ]
    try:
        response = await chat.ainvoke(messages)
    except Exception as e:
        settings = get_settings()
        logger.error("LLM 调用失败 model=%s error=%s", model or settings.qwen_model, e)
        raise LLMException(f"AI 服务暂时不可用：{e}")

    content = response.content
    if not content or not isinstance(content, str):
        raise LLMException("LLM 响应格式异常：content 为空")
    return content


async def ping_llm() -> bool:
    """探活：发一个极短请求确认 LLM 可达，失败返回 False。"""
    try:
        chat = get_chat_model(max_retries=0)
        await chat.ainvoke([HumanMessage(content="ping")])
        return True
    except Exception as e:
        logger.warning("LLM 探活失败: %s", e)
        return False
