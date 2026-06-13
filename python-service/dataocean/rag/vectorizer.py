"""Vector indexing logic for RAG chunks."""

from __future__ import annotations

import asyncio
import logging
from time import perf_counter

from pymilvus import Collection

from dataocean.infra.embeddings import embed_texts, embed_texts_with_config

from .milvus_client import ensure_collection
from .schema import ChunkItem, EmbeddingConfig, VectorizeResponse
from .vector_store import add_chunk_embeddings, delete_by_expr

logger = logging.getLogger(__name__)


async def vectorize_chunks(
    datasource_id: int,
    snapshot_id: int,
    version_no: int,
    chunks: list[ChunkItem],
    doc_id: int | None = None,
    previous_version_no: int | None = None,
    force: bool = False,
    target_collection: str | None = None,
    target_dimension: int | None = None,
    embedding_config: EmbeddingConfig | None = None,
) -> VectorizeResponse:
    """Embed chunks and write them through LangChain's Milvus VectorStore."""
    start = perf_counter()
    if not chunks:
        return VectorizeResponse(duration_ms=_elapsed_ms(start))

    if doc_id is None:
        logger.warning("vectorize_chunks called without doc_id; using 0 as placeholder")

    try:
        collection = await asyncio.to_thread(ensure_collection, target_collection, target_dimension)
    except Exception as exc:
        logger.error("Milvus connection failed: %s", exc)
        return VectorizeResponse(
            status="FAILED",
            failed_count=len(chunks),
            errors=[f"Milvus connection failed: {exc}"],
            duration_ms=_elapsed_ms(start),
        )

    # 安全修复：不允许在没有 doc_id 的情况下进行 force 先删后写
    # 否则会导致整个 datasource 的向量被删除且无法恢复
    if force and doc_id is None:
        logger.error(
            "vectorize_chunks force=True requires doc_id; "
            "datasource-level force delete is not allowed without staging"
        )
        return VectorizeResponse(
            status="FAILED",
            failed_count=len(chunks),
            errors=[
                "force=True requires doc_id to prevent data loss. "
                "For datasource-level rebuild, use staging mode: "
                "write new vectors first, verify, then delete old ones."
            ],
            duration_ms=_elapsed_ms(start),
        )

    cleanup_same_doc_version_after_write = force and doc_id is not None

    texts = [chunk.chunk_text for chunk in chunks]
    try:
        if embedding_config is not None:
            embeddings = await embed_texts_with_config(texts, embedding_config)
        else:
            embeddings = await embed_texts(texts)
    except Exception as exc:
        logger.error("Embedding generation failed: %s", exc)
        return VectorizeResponse(
            status="FAILED",
            failed_count=len(chunks),
            errors=[f"Embedding failed: {exc}"],
            duration_ms=_elapsed_ms(start),
        )

    if len(embeddings) != len(chunks):
        msg = f"Embedding count mismatch: chunks={len(chunks)} embeddings={len(embeddings)}"
        logger.error(msg)
        return VectorizeResponse(
            status="FAILED",
            failed_count=len(chunks),
            errors=[msg],
            duration_ms=_elapsed_ms(start),
        )

    metadatas = [
        {
            "datasource_id": datasource_id,
            "snapshot_id": snapshot_id,
            "knowledge_version_no": version_no,
            "doc_id": doc_id or 0,
            "source_id": chunk.source_id or 0,
            "chunk_type": chunk.chunk_type,
            "governance_status": chunk.governance_status,
            "review_status": chunk.review_status,
            "related_table": chunk.related_table,
            "related_column": chunk.related_column,
        }
        for chunk in chunks
    ]

    try:
        inserted_ids = await add_chunk_embeddings(
            texts=[chunk.chunk_text[:8192] for chunk in chunks],
            embeddings=embeddings,
            metadatas=metadatas,
            collection_name=target_collection,
            dimension=target_dimension,
        )
        cleanup_completed = False
        if cleanup_same_doc_version_after_write:
            try:
                cleanup_expr = _old_doc_version_vectors_expr(datasource_id, doc_id, version_no, inserted_ids)
                await delete_by_expr(cleanup_expr, target_collection)
                cleanup_completed = True
            except Exception as exc:
                logger.warning("Old force-rebuild vectors cleanup skipped after successful write: %s", exc)
        if doc_id is not None:
            try:
                await asyncio.to_thread(
                    _verify_doc_version_at_least if cleanup_same_doc_version_after_write and not cleanup_completed
                    else _verify_doc_version,
                    collection,
                    datasource_id,
                    doc_id,
                    version_no,
                    len(chunks),
                )
            except Exception:
                if not cleanup_same_doc_version_after_write:
                    await delete_by_expr(_doc_version_expr(datasource_id, doc_id, version_no), target_collection)
                raise

        logger.info("Vector write succeeded datasource_id=%d count=%d", datasource_id, len(chunks))
        return VectorizeResponse(
            status="COMPLETED",
            success_count=len(chunks),
            duration_ms=_elapsed_ms(start),
        )
    except Exception as exc:
        logger.error("Milvus write failed: %s", exc)
        return VectorizeResponse(
            status="FAILED",
            failed_count=len(chunks),
            errors=[f"write failed: {exc}"],
            duration_ms=_elapsed_ms(start),
        )


def switch_version(
    collection: Collection,
    datasource_id: int,
    new_snapshot_id: int,
    old_snapshot_id: int,
    expected_count: int,
) -> None:
    """Delete an old snapshot only after the new snapshot vector count matches."""
    actual_count = _count_vectors(
        collection,
        expr=f"datasource_id == {datasource_id} and snapshot_id == {new_snapshot_id}",
    )
    if actual_count != expected_count:
        raise ValueError(
            f"new snapshot vector count mismatch expected={expected_count} actual={actual_count}"
        )

    collection.delete(expr=f"datasource_id == {datasource_id} and snapshot_id == {old_snapshot_id}")
    collection.flush()
    logger.info(
        "Snapshot vector switch completed datasource_id=%d old=%d new=%d count=%d",
        datasource_id,
        old_snapshot_id,
        new_snapshot_id,
        actual_count,
    )


def cleanup_old_versions(
    collection: Collection, datasource_id: int, current_snapshot_id: int
) -> None:
    """Delete old datasource vectors while keeping the active snapshot."""
    collection.delete(expr=f"datasource_id == {datasource_id} and snapshot_id != {current_snapshot_id}")
    collection.flush()
    logger.info(
        "Old snapshot vectors cleaned datasource_id=%d keep_snapshot=%d",
        datasource_id,
        current_snapshot_id,
    )


def _switch_doc_version(
    collection: Collection,
    datasource_id: int,
    doc_id: int,
    new_version_no: int,
    old_version_no: int,
    expected_count: int,
) -> None:
    """Delete old document-version vectors after the new version count matches."""
    actual_count = _count_vectors(
        collection,
        expr=_doc_version_expr(datasource_id, doc_id, new_version_no),
    )
    if actual_count != expected_count:
        raise ValueError(
            f"new document vector count mismatch expected={expected_count} actual={actual_count}"
        )

    collection.delete(expr=_doc_version_expr(datasource_id, doc_id, old_version_no))
    collection.flush()
    logger.info(
        "Document vector switch completed datasource_id=%d doc_id=%d old=%d new=%d count=%d",
        datasource_id,
        doc_id,
        old_version_no,
        new_version_no,
        actual_count,
    )


def _verify_doc_version(
    collection: Collection,
    datasource_id: int,
    doc_id: int,
    version_no: int,
    expected_count: int,
) -> None:
    """Verify that the new document-version vectors were fully written."""
    actual_count = _count_vectors(
        collection,
        expr=_doc_version_expr(datasource_id, doc_id, version_no),
    )
    if actual_count != expected_count:
        raise ValueError(
            f"new document vector count mismatch expected={expected_count} actual={actual_count}"
        )


def _verify_doc_version_at_least(
    collection: Collection,
    datasource_id: int,
    doc_id: int,
    version_no: int,
    expected_count: int,
) -> None:
    """Verify new vectors exist when old-version cleanup could not be completed."""
    actual_count = _count_vectors(
        collection,
        expr=_doc_version_expr(datasource_id, doc_id, version_no),
    )
    if actual_count < expected_count:
        raise ValueError(
            f"new document vector count below expected minimum={expected_count} actual={actual_count}"
        )


def _delete_by_doc(collection: Collection, datasource_id: int, doc_id: int) -> None:
    collection.delete(expr=f"datasource_id == {datasource_id} and doc_id == {doc_id}")
    collection.flush()
    logger.info("Document vectors deleted datasource_id=%d doc_id=%d", datasource_id, doc_id)


def _delete_doc_version(
    collection: Collection, datasource_id: int, doc_id: int | None, version_no: int
) -> None:
    if doc_id is None:
        return
    collection.delete(expr=_doc_version_expr(datasource_id, doc_id, version_no))
    collection.flush()
    logger.warning(
        "Failed document-version vectors cleaned datasource_id=%d doc_id=%d version_no=%d",
        datasource_id,
        doc_id,
        version_no,
    )


def _delete_by_datasource(collection: Collection, datasource_id: int) -> None:
    collection.delete(expr=f"datasource_id == {datasource_id}")
    collection.flush()
    logger.info("Datasource vectors deleted datasource_id=%d", datasource_id)


def _count_vectors(collection: Collection, expr: str) -> int:
    """Query Milvus aggregate count across pymilvus response key variants."""
    rows = collection.query(expr=expr, output_fields=["count(*)"])
    if not rows:
        return 0
    row = rows[0]
    for key in ("count(*)", "count"):
        if key in row:
            return int(row[key])
    return 0


def _doc_version_expr(datasource_id: int, doc_id: int | None, version_no: int) -> str:
    return (
        f"datasource_id == {datasource_id} "
        f"and doc_id == {doc_id} "
        f"and knowledge_version_no == {version_no}"
    )


def _old_doc_version_vectors_expr(
    datasource_id: int,
    doc_id: int,
    version_no: int,
    inserted_ids: list[str],
) -> str:
    new_ids = []
    for vector_id in inserted_ids:
        try:
            new_ids.append(str(int(vector_id)))
        except (TypeError, ValueError):
            logger.warning("Skipping non-numeric Milvus vector id during cleanup: %s", vector_id)
    if not new_ids:
        raise ValueError("Milvus did not return inserted vector ids for force rebuild cleanup")
    return f"{_doc_version_expr(datasource_id, doc_id, version_no)} and id not in [{', '.join(new_ids)}]"


def _elapsed_ms(start: float) -> int:
    return int((perf_counter() - start) * 1000)
