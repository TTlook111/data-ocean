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

_JOIN_KEYWORDS = frozenset([
    "\u5173\u8054", "join", "\u8fde\u63a5", "\u5bf9\u5e94", "\u5c5e\u4e8e",
    "\u5305\u542b", "\u5173\u7cfb", "\u548c", "\u4e0e", "\u4ee5\u53ca",
    "\u540c\u65f6", "\u8de8\u8868",
])


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
        has_join = _has_intent(question, _JOIN_KEYWORDS) or len(question_keywords) >= 4
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
            if "deprecated" in document.page_content.lower():
                weighted_score -= 0.5
            weighted_score += _chunk_type_bonus(chunk_type, has_aggregation, has_caution, has_join)

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
    return any(keyword in question for keyword in keywords)
