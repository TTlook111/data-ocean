"""LangChain-style reranker for DataOcean RAG results."""

from __future__ import annotations

import logging
from collections.abc import Sequence
from typing import Any

from langchain_core.documents import Document
from langchain_core.documents.compressor import BaseDocumentCompressor

from .schema import RetrievedSchema, RetrieveRequest

logger = logging.getLogger(__name__)

_AGGREGATION_KEYWORDS = frozenset([
    "\u603b", "\u5408\u8ba1", "\u5e73\u5747", "\u5747\u503c", "\u5360\u6bd4", "\u6bd4\u4f8b",
    "\u589e\u957f", "\u540c\u6bd4", "\u73af\u6bd4", "\u6700\u5927", "\u6700\u5c0f",
    "\u6392\u540d", "top", "\u7edf\u8ba1", "\u6c47\u603b", "sum", "avg", "count",
    "max", "min", "\u603b\u6570", "\u6570\u91cf", "\u91d1\u989d",
])

_CAUTION_KEYWORDS = frozenset([
    "\u6ce8\u610f", "\u533a\u522b", "\u533a\u5206", "\u4e0d\u540c", "\u5dee\u5f02",
    "\u5bb9\u6613\u9519", "\u6613\u6df7", "\u9677\u9631", "\u5751", "\u8bef\u7528",
    "\u6b63\u786e", "\u5e94\u8be5\u7528", "\u4e0d\u80fd\u7528",
])

# JOIN \u610f\u56fe\u5173\u952e\u8bcd\u2014\u2014\u4ec5\u4fdd\u7559\u9ad8\u7cbe\u5ea6\u6a21\u5f0f\uff0c\u79fb\u9664"\u548c"/"\u4e0e"/"\u5305\u542b"\u7b49\u901a\u7528\u8fde\u8bcd
# \u907f\u514d"\u8ba2\u5355\u91d1\u989d\u548c\u9000\u6b3e\u91d1\u989d"\u8fd9\u7c7b\u975e JOIN \u67e5\u8be2\u88ab\u8bef\u5224
_JOIN_KEYWORDS = frozenset([
    "join", "\u5173\u8054\u67e5\u8be2", "\u8de8\u8868\u67e5\u8be2", "\u591a\u8868\u67e5\u8be2",
    "\u5de6\u8fde\u63a5", "\u53f3\u8fde\u63a5", "\u5185\u8fde\u63a5", "\u5916\u8fde\u63a5",
    "left join", "right join", "inner join", "outer join",
])

# JOIN \u6a21\u5f0f\u5339\u914d\u2014\u2014\u9700\u8981\u642d\u914d\u4e0a\u4e0b\u6587\u624d\u89c6\u4e3a JOIN \u610f\u56fe\uff08\u5982"\u5173\u8054XX\u8868"\uff09
_JOIN_PATTERNS = ["\u5173\u8054", "\u8fde\u63a5"]


class DataOceanReranker(BaseDocumentCompressor):
    """Business-rule reranker compatible with LangChain retriever pipelines."""

    top_k: int = 10
    confidence_scores: dict[str, int] | None = None

    def compress_documents(
        self,
        documents: Sequence[Document],
        query: str,
        callbacks: Any | None = None,
    ) -> Sequence[Document]:
        question = query.lower()
        question_keywords = set(question.split())
        has_aggregation = _has_intent(question, _AGGREGATION_KEYWORDS)
        has_caution = _has_intent(question, _CAUTION_KEYWORDS)
        # JOIN 意图：精确关键词命中，或"关联/连接"搭配表名上下文
        has_join = _has_intent(question, _JOIN_KEYWORDS) or _has_join_pattern(question)
        confidence_scores = self.confidence_scores or {}

        scored_documents: list[tuple[float, Document]] = []
        for document in documents:
            metadata = dict(document.metadata)
            base_score = float(metadata.get("score", metadata.get("relevance_score", 0.0)) or 0.0)
            table_name = str(metadata.get("table_name") or metadata.get("related_table") or "")
            chunk_type = str(metadata.get("chunk_type") or "")
            governance_status = str(metadata.get("governance_status") or "")

            weighted_score = base_score
            if table_name and table_name.lower() in question_keywords:
                weighted_score += 0.2
            if confidence_scores.get(table_name, 0) > 80:
                weighted_score += 0.1
            if governance_status == "RECOMMENDED":
                weighted_score += 0.05
            # deprecated 惩罚：同时检查治理状态 metadata 和文本内容，避免误判
            if governance_status == "DEPRECATED" or "deprecated" in document.page_content.lower():
                weighted_score -= 0.5
            bonus = _chunk_type_bonus(chunk_type, has_aggregation, has_caution, has_join)

            # 限制 bonus 总和上限为 0.25，防止重排过度偏离向量相似度排序
            bonus = min(bonus, 0.25)
            weighted_score += bonus

            # 安全修复：clamp 到 [0, 1.0]，避免重排分数失真
            weighted_score = max(0.0, min(1.0, weighted_score))

            metadata["score"] = round(weighted_score, 4)
            metadata.setdefault("relevance_score", base_score)
            scored_documents.append((weighted_score, Document(page_content=document.page_content, metadata=metadata)))

        scored_documents.sort(key=lambda item: item[0], reverse=True)
        compressed = [document for _, document in scored_documents[: self.top_k]]

        logger.info(
            "Rerank completed input=%d output=%d top_score=%.4f agg=%s join=%s caution=%s",
            len(documents),
            len(compressed),
            compressed[0].metadata.get("score", 0.0) if compressed else 0.0,
            has_aggregation,
            has_join,
            has_caution,
        )
        return compressed


def rerank(
    results: list[RetrievedSchema],
    request: RetrieveRequest,
) -> list[RetrievedSchema]:
    """Compatibility wrapper returning RetrievedSchema objects."""
    documents = [_schema_to_document(item) for item in results]
    compressor = DataOceanReranker(
        top_k=request.top_k,
        confidence_scores=request.confidence_scores or {},
    )
    ranked_documents = compressor.compress_documents(documents, request.question)
    return [_document_to_schema(document) for document in ranked_documents]


def _schema_to_document(item: RetrievedSchema) -> Document:
    """将 RetrievedSchema 转换为 LangChain Document 格式

    用于重排器内部处理，将业务模型转换为通用文档格式。
    """
    return Document(
        page_content=item.chunk_text,
        metadata={
            "table_name": item.table_name,
            "columns": [column.model_dump() for column in item.columns],
            "score": item.score,
            "relevance_score": item.relevance_score if item.relevance_score is not None else item.score,
            "chunk_type": item.chunk_type,
            "source_type": item.source_type,
            "source_version": item.source_version,
            "snapshot_id": item.snapshot_id,
            "governance_status": item.governance_status,
            "review_status": item.review_status,
        },
    )


def _document_to_schema(document: Document) -> RetrievedSchema:
    """将 LangChain Document 转换为 RetrievedSchema 格式

    用于重排器输出，将通用文档格式转换回业务模型。
    """
    metadata = document.metadata
    return RetrievedSchema(
        table_name=metadata.get("table_name", ""),
        columns=metadata.get("columns", []),
        score=metadata.get("score", 0.0),
        relevance_score=metadata.get("relevance_score", metadata.get("score", 0.0)),
        chunk_type=metadata.get("chunk_type", ""),
        source_type=metadata.get("source_type", "SCHEMA"),
        source_version=metadata.get("source_version", 0),
        snapshot_id=metadata.get("snapshot_id"),
        chunk_text=document.page_content,
        governance_status=metadata.get("governance_status", ""),
        review_status=metadata.get("review_status", ""),
    )


def _chunk_type_bonus(
    chunk_type: str,
    has_aggregation: bool,
    has_caution: bool,
    has_join: bool,
) -> float:
    """根据 chunk 类型和查询意图计算加分

    不同类型的 chunk 在特定查询场景下有不同的加分：
    - JOIN_PATH：关联类查询加分（用户问跨表问题时）
    - METRIC：聚合类查询加分（用户问统计问题时）
    - FIELD_NOTE：注意事项类查询加分（用户问字段区别时）
    - QUERY_SCENE：查询场景类加分（通用加分）
    """
    bonus = 0.0
    if chunk_type == "JOIN_PATH" and has_join:
        bonus += 0.15
    if chunk_type == "METRIC" and has_aggregation:
        bonus += 0.15
    if chunk_type == "FIELD_NOTE" and has_caution:
        bonus += 0.1
    if chunk_type == "QUERY_SCENE":
        bonus += 0.08
    return bonus


def _has_intent(question: str, keywords: frozenset[str]) -> bool:
    """检查问题中是否包含指定关键词（用于意图识别）"""
    return any(keyword in question for keyword in keywords)


def _has_join_pattern(question: str) -> bool:
    """检查问题是否包含 JOIN 模式（"关联XX表"、"连接XX和XX"等）

    通用词"关联"/"连接"必须搭配表名上下文才视为 JOIN 意图，
    避免"关联指标"等非 JOIN 用法被误判。
    """
    _table_context = ("表", "查询", "join", "数据", "orders", "customer", "order")
    for pattern in _JOIN_PATTERNS:
        idx = question.find(pattern)
        if idx == -1:
            continue
        # 检查右侧上下文（20 字符窗口，覆盖较长的英文表名）
        after = question[idx + len(pattern):idx + len(pattern) + 20]
        if any(kw in after for kw in _table_context):
            return True
        # 检查左侧上下文（"订单表关联" 语序）
        before = question[max(0, idx - 10):idx]
        if "表" in before:
            return True
    return False
