"""RAG 规则加权重排器

基于 Milvus 相似度分数，叠加业务规则加权：
- 表名命中用户问题关键词 +0.2
- 字段可信度 > 80 的 +0.1
- governance_status=RECOMMENDED +0.05
- chunk_type 场景加权（JOIN_PATH/METRIC/FIELD_NOTE/QUERY_SCENE）
- 废弃字段 -0.5
"""

import logging
import re

from .schema import RetrievedSchema, RetrieveRequest

logger = logging.getLogger(__name__)

# 聚合意图关键词（用户问题中出现这些词时，METRIC chunk 加分）
_AGGREGATION_KEYWORDS = frozenset([
    "总", "合计", "平均", "均值", "占比", "比例", "增长", "同比", "环比",
    "最多", "最少", "最大", "最小", "排名", "top", "统计", "汇总",
    "sum", "avg", "count", "max", "min", "总数", "数量", "金额",
])

# 注意/防坑意图关键词
_CAUTION_KEYWORDS = frozenset([
    "注意", "区别", "区分", "不同", "差异", "容易错", "易混", "陷阱",
    "坑", "误用", "正确", "应该用", "不能用",
])

# 多表查询意图关键词（出现这些词时，JOIN_PATH chunk 加分）
_JOIN_KEYWORDS = frozenset([
    "关联", "join", "连接", "对应", "属于", "包含", "关系",
    "和", "与", "以及", "同时", "跨表",
])


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
    question = request.question.lower()
    question_keywords = set(question.split())
    confidence_scores = request.confidence_scores or {}

    # 预判用户查询意图
    has_aggregation = _has_intent(question, _AGGREGATION_KEYWORDS)
    has_caution = _has_intent(question, _CAUTION_KEYWORDS)
    has_join = _has_intent(question, _JOIN_KEYWORDS) or len(question_keywords) >= 4

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

        # 规则 3：RECOMMENDED 状态 +0.05
        if item.governance_status == "RECOMMENDED":
            score += 0.05

        # 规则 4：废弃字段惩罚（理论上已被 Milvus 过滤，这里做兜底）
        if "deprecated" in item.chunk_text.lower():
            score -= 0.5

        # 规则 5：chunk_type 场景加权
        score += _chunk_type_bonus(item.chunk_type, has_aggregation, has_caution, has_join)

        scored_results.append((score, item))

    # 按加权分数降序排列
    scored_results.sort(key=lambda x: x[0], reverse=True)

    # 截断到 top_k，使用 model_copy 避免修改原始对象的 score
    top_k = request.top_k
    final_results = []
    for weighted_score, item in scored_results[:top_k]:
        final_results.append(item.model_copy(update={"score": round(weighted_score, 4)}))

    logger.info(
        "重排完成 input=%d output=%d top_score=%.4f agg=%s join=%s caution=%s",
        len(results),
        len(final_results),
        final_results[0].score if final_results else 0,
        has_aggregation,
        has_join,
        has_caution,
    )
    return final_results


def _chunk_type_bonus(
    chunk_type: str,
    has_aggregation: bool,
    has_caution: bool,
    has_join: bool,
) -> float:
    """根据 chunk_type 和用户查询意图计算额外加分"""
    bonus = 0.0

    if chunk_type == "JOIN_PATH" and has_join:
        bonus += 0.15

    if chunk_type == "METRIC" and has_aggregation:
        bonus += 0.15

    if chunk_type == "FIELD_NOTE" and has_caution:
        bonus += 0.1

    if chunk_type == "QUERY_SCENE":
        # 查询场景 chunk 对复杂查询有通用价值
        bonus += 0.08

    return bonus


def _has_intent(question: str, keywords: frozenset) -> bool:
    """检查问题中是否包含指定意图的关键词"""
    return any(kw in question for kw in keywords)
