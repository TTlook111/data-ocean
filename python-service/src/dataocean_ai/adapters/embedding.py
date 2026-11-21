"""使用受控配置和单次请求预算生成向量。"""

import asyncio
import math

import httpx
from langchain_openai import OpenAIEmbeddings
from openai import APIError

from dataocean_ai.adapters.chat import ModelConfiguration, ModelFailure


async def embed(config: ModelConfiguration, texts: list[str]) -> list[list[float]]:
    """在相同模型配置下向量化完整文本，拒绝不一致的结果。"""
    if config.protocol != "OPENAI_EMBEDDINGS" or not texts:
        # 调用入口自己核对协议和业务输入。
        raise ModelFailure("MODEL_FORMAT")
    try:
        # 由调用者控制批量。SDK 的默认重复调用、截断和代理回退不参与构建。
        async with (
            asyncio.timeout(config.timeout),
            httpx.AsyncClient(
                follow_redirects=False, trust_env=False, timeout=config.timeout
            ) as client,
        ):
            model = OpenAIEmbeddings(
                model=config.model_name,
                base_url=config.endpoint,
                api_key=config.api_key,
                timeout=config.timeout,
                max_retries=0,
                check_embedding_ctx_length=False,
                chunk_size=len(texts),
                http_async_client=client,
            )
            rows = await model.aembed_documents(texts)
    except APIError as error:
        # 诊断只披露服务故障的安全分类，不保存上游正文或凭据。
        codes = {400: "MODEL_FORMAT", 401: "MODEL_AUTH", 403: "MODEL_AUTH", 429: "MODEL_RATE_LIMIT"}
        raise ModelFailure(
            codes.get(getattr(error, "status_code", None), "MODEL_RESULT_UNKNOWN")
        ) from None
    except (httpx.RequestError, TimeoutError):
        # 回包无法核实，不自动以另一模型或另一请求重发。
        raise ModelFailure("MODEL_RESULT_UNKNOWN") from None
    except (TypeError, ValueError, IndexError):
        # 响应结构异常时不把提供方原文交给业务层。
        raise ModelFailure("MODEL_FORMAT") from None
    if not rows or len(rows) != len(texts) or not rows[0]:
        # 完整片段必须逐条有可用向量。
        raise ModelFailure("MODEL_FORMAT")
    dimension = len(rows[0])
    if dimension > 32768 or any(
        len(row) != dimension or not all(math.isfinite(value) for value in row) or not any(row)
        for row in rows
    ):
        # 余弦索引拒绝零向量、非有限数和混合维度。
        raise ModelFailure("MODEL_FORMAT")
    return rows
