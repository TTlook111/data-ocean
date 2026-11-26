"""用固定合成请求验证已保存的模型配置。"""

from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field

from dataocean_ai.adapters.chat import ChatConfiguration, ModelConfiguration, ModelFailure, generate
from dataocean_ai.adapters.embedding import embed
from dataocean_ai.adapters.reranking import rerank
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
    if config.protocol == "OPENAI_EMBEDDINGS":
        # 测试与实际候选构建共享同一向量发送和校验逻辑。
        await embed(config, ["DataOcean connection test"])
        return
    # 连接测试与正式排序共用传输和结果校验，仅替换为合成输入。
    await rerank(config, "Product stock", ["Product stock is available", "Warehouse location"])
