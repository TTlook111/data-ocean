"""对 Java 已核对的同源原问和候选计算语义相似度。"""

import math
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field

from dataocean_ai.adapters.chat import ModelConfiguration, ModelFailure
from dataocean_ai.adapters.embedding import embed
from dataocean_ai.adapters.model_receipts import model_call_scope
from dataocean_ai.governance.security import verify_java

# 保持反馈业务节点独立，机器身份仍沿用服务间认证。
router = APIRouter(prefix="/internal/v1/feedback", dependencies=[Depends(verify_java)])

# 原问和补充合并后仍须满足单条输入上限。
type Question = Annotated[str, Field(min_length=1, max_length=2500)]


class SimilarityModel(ModelConfiguration):
    """相似检索只接收已分配的向量配置。"""

    # 生成与重排配置不能用于本节点。
    protocol: Literal["OPENAI_EMBEDDINGS"]


class SimilarityRequest(BaseModel):
    """一次排序只接收最小的原问和已获准候选。"""

    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)

    # 用于模型用量和故障回查的原事项编号。
    issueId: str = Field(pattern=r"^[0-9]{1,19}$")  # noqa: N815
    # 当前独立节点签发的模型配置。
    model: SimilarityModel
    # 原问以及仍有权回看的补充条件。
    question: Question
    # 次序由 Java 固定，返回相对于该列表的索引。
    candidates: list[Question] = Field(min_length=1, max_length=7)


class SimilarityOrder(BaseModel):
    """只返回候选位置，避免把业务正文带入内部响应。"""

    # 位置从零开始，数量最多五条。
    order: list[int]


def unit_vector(vector: list[float]) -> list[float]:
    """使用同一维度向量的方向，忽略模型产生的量级差异。"""
    # 先按最大分量缩放，避免有限但极端的值造成范数溢出。
    scale = max(map(abs, vector))
    scaled = [value / scale for value in vector]
    norm = math.hypot(*scaled)
    return [value / norm for value in scaled]


@router.post("/similarity", response_model=SimilarityOrder)
async def similarity(request: Request) -> SimilarityOrder:
    """单批向量排序，低相关项留给原有手动搜索。"""
    try:
        # 避免请求校验错误响应包含凭据或原问题。
        job = SimilarityRequest.model_validate(await request.json())
    except ValueError:
        # 只报告稳定的跨端校验分类。
        raise HTTPException(status_code=422, detail="FEEDBACK_SIMILARITY_INPUT_INVALID") from None
    try:
        # 向量仅在请求中使用，实际提供方调用继续进入运行概览。
        with model_call_scope("FEEDBACK", "feedback-similarity", job.issueId):
            vectors = await embed(job.model, [job.question, *job.candidates])
    except ModelFailure:
        # 失败交回现有手工核查路径，响应不携带上游正文。
        raise HTTPException(status_code=503, detail="FEEDBACK_SIMILARITY_UNAVAILABLE") from None

    target = unit_vector(vectors[0])
    scores = [
        (index, math.fsum(a * b for a, b in zip(target, unit_vector(vector), strict=True)))
        for index, vector in enumerate(vectors[1:])
    ]
    # 相同相似度保留 Java 候选次序。阈值只筛候选，处理人仍核对实际根因。
    relevant = sorted(scores, key=lambda entry: -entry[1])
    return SimilarityOrder(order=[index for index, score in relevant if score >= 0.7][:5])
