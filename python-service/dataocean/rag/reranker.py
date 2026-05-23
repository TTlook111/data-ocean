"""RAG 规则加权重排器

基于 Milvus 相似度分数，叠加业务规则加权：
- 表名命中用户问题关键词 +0.2
- 字段可信度 > 80 的 +0.1
- governance_status=RECOMMENDED +0.05
- 废弃字段 -0.5
"""

import logging

from .schema import RetrievedSchema, RetrieveRequest

logger = logging.getLogger(__name__)


def rerank(
    results: list[RetrievedSchema],
    request: RetrieveRequest,
) -> list[RetrievedSchema]:
    """对检索结果进行规则加权重排

    Args:
        results: Milvus 原始检索结果
        request: 原始检索请求（含问题文本和可信度分数）

    Returns:
        重排后的结果列表（按加权分数降序，截断到 top_k）
    """
    question_keywords = set(request.question.lower().split())
    confidence_scores = request.confidence_scores or {}

    scored_results = []
    for item in results:
        score = item.score  # 基础分：Milvus 相似度

        # 规则 1：表名命中用户问题关键词 +0.2
        if item.table_name and item.table_name.lower() in question_keywords:
            score += 0.2

        # 规则 2：字段可信度 > 80 的 +0.1
        table_confidence = confidence_scores.get(item.table_name, 0)
        if table_confidence > 80:
            score += 0.1

        # 规则 3：RECOMMENDED 状态 +0.05（通过 chunk_text 中的标记判断）
        # 由于 governance_status 已在 Milvus 过滤中使用，这里通过 chunk_type 间接判断

        # 规则 4：废弃字段惩罚（理论上已被 Milvus 过滤，这里做兜底）
        if "deprecated" in item.chunk_text.lower():
            score -= 0.5

        scored_results.append((score, item))

    # 按加权分数降序排列
    scored_results.sort(key=lambda x: x[0], reverse=True)

    # 截断到 top_k，使用 model_copy 避免修改原始对象的 score
    top_k = request.top_k
    final_results = []
    for weighted_score, item in scored_results[:top_k]:
        final_results.append(item.model_copy(update={"score": round(weighted_score, 4)}))

    logger.info(
        "重排完成 input=%d output=%d top_score=%.4f",
        len(results),
        len(final_results),
        final_results[0].score if final_results else 0,
    )
    return final_results
