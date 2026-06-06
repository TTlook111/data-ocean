"""Schema RAG retriever backed by LangChain Milvus VectorStore."""

from __future__ import annotations

import logging

from .schema import RetrievedSchema, RetrieveRequest
from .vector_store import search_by_vector

logger = logging.getLogger(__name__)


async def retrieve_from_milvus(
    question_embedding: list[float], request: RetrieveRequest
) -> list[RetrievedSchema]:
    """Retrieve schema chunks with enforced datasource/snapshot/admission filters."""
    hits = await search_by_vector(
        embedding=question_embedding,
        datasource_id=request.datasource_id,
        snapshot_id=request.active_snapshot_id,
        limit=request.top_k * 2,
    )

    retrieved: list[RetrievedSchema] = []
    for hit in hits:
        metadata = hit.document.metadata
        related_column = metadata.get("related_column", "")
        retrieved.append(
            RetrievedSchema(
                table_name=metadata.get("related_table", ""),
                columns=[
                    col.strip()
                    for col in related_column.split(",")
                    if col.strip()
                ]
                if related_column
                else [],
                score=hit.score,
                relevance_score=hit.score,
                chunk_type=metadata.get("chunk_type", ""),
                source_version=metadata.get("knowledge_version_no", 0),
                snapshot_id=metadata.get("snapshot_id"),
                chunk_text=hit.document.page_content,
                governance_status=metadata.get("governance_status", ""),
                review_status=metadata.get("review_status", ""),
            )
        )

    logger.info(
        "Milvus retrieval completed via LangChain datasource_id=%d results=%d",
        request.datasource_id,
        len(retrieved),
    )
    return retrieved
