"""用固定合成请求验证已保存的模型配置。"""

import asyncio
import math
from typing import Literal

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request
from langchain_openai import OpenAIEmbeddings
from openai import APIError as OpenAIAPIError
from pydantic import BaseModel, Field

from dataocean_ai.adapters.chat import ChatConfiguration, ModelConfiguration, ModelFailure, generate
from dataocean_ai.governance.security import verify_java

# 沿用调用方服务身份，仅允许 Java 提交已经过准入核对的配置。
router = APIRouter(prefix="/internal/v1/models", dependencies=[Depends(verify_java)])


class ProbeAnswer(BaseModel):
    """证明服务能够按结构返回固定的合成内容。"""

    # 固定值用来核对服务确实执行了测试请求。
    check: Literal["ok"]


class ProbeResult(BaseModel):
    """连接测试的受控诊断结果。"""

    # 测试成功与否独立于模型的启停状态。
    passed: bool

    # 失败时只返回固定分类。
    failure_code: str | None = Field(default=None, serialization_alias="failureCode")


@router.post("/connection-test", response_model=ProbeResult)
async def test_connection(request: Request) -> ProbeResult:
    """复用业务模型调用所用的协议、结构化解析和传输约束。"""
    try:
        # 验证失败时不将含凭据的原始请求体放入错误响应。
        config = ModelConfiguration.model_validate(await request.json())
    except ValueError:
        # 内部调用者可据此核对跨端结构。
        raise HTTPException(status_code=422, detail="INVALID_MODEL_CONFIGURATION") from None
    try:
        if config.protocol in {"OPENAI_CHAT_COMPLETIONS", "ANTHROPIC_MESSAGES"}:
            # 生成服务使用相同适配器核对结构化能力。
            chat = ChatConfiguration.model_validate(config.model_dump(by_alias=True))
            await generate(chat, '只返回 JSON 对象 {"check":"ok"}。', "连接测试", ProbeAnswer)
        else:
            # 向量化和重排同样只读取受控配置，测试不访问业务样本。
            await _check_non_chat(config)
    except ModelFailure as failure:
        # 上游只接收稳定原因，提供方诊断原文留在调用边界外。
        return ProbeResult(passed=False, failure_code=str(failure))
    return ProbeResult(passed=True)


async def _check_non_chat(config: ModelConfiguration) -> None:
    """以合成文本验证模型返回确实符合所选能力。"""
    try:
        # 复用生成调用的出站约束，避免读取开发机代理或跟随重定向。
        async with (
            asyncio.timeout(config.timeout),
            httpx.AsyncClient(
                follow_redirects=False, trust_env=False, timeout=config.timeout
            ) as transport,
        ):
            if config.protocol == "OPENAI_EMBEDDINGS":
                # 兼容服务收到一个原始文本输入，无本地分词或供应商重试。
                model = OpenAIEmbeddings(
                    model=config.model_name,
                    base_url=config.endpoint,
                    api_key=config.api_key,
                    timeout=config.timeout,
                    max_retries=0,
                    check_embedding_ctx_length=False,
                    http_async_client=transport,
                )
                vector = await model.aembed_query("DataOcean connection test")
                if not vector or not all(math.isfinite(value) for value in vector):
                    raise ModelFailure("MODEL_FORMAT")
                return

            # Cohere v2 在配置基址下接收固定查询和两个合成文本。
            response = await transport.post(
                config.endpoint.rstrip("/") + "/rerank",
                headers={"Authorization": "Bearer " + config.api_key.get_secret_value()},
                json={
                    "model": config.model_name,
                    "query": "Product stock",
                    "documents": ["Product stock is available", "Warehouse location"],
                    "top_n": 1,
                },
            )
            response.raise_for_status()
            results = response.json()["results"]
            if (
                len(results) != 1
                or results[0]["index"] not in (0, 1)
                or not math.isfinite(results[0]["relevance_score"])
            ):
                raise ModelFailure("MODEL_FORMAT")
    except (ValueError, TypeError, KeyError, IndexError):
        # 无效结构只暴露稳定分类，不返回正文。
        raise ModelFailure("MODEL_FORMAT") from None
    except (httpx.HTTPStatusError, OpenAIAPIError) as error:
        # HTTP 与 SDK 连接测试使用一致的安全故障编码。
        status = (
            error.response.status_code
            if isinstance(error, httpx.HTTPStatusError)
            else getattr(error, "status_code", None)
        )
        codes = {400: "MODEL_FORMAT", 401: "MODEL_AUTH", 403: "MODEL_AUTH", 429: "MODEL_RATE_LIMIT"}
        raise ModelFailure(codes.get(status, "MODEL_RESULT_UNKNOWN")) from None
    except (httpx.RequestError, TimeoutError):
        # 传输异常没有可验证的服务回执。
        raise ModelFailure("MODEL_RESULT_UNKNOWN") from None
