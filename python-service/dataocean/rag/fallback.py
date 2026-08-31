"""RAG 降级方案

当 Milvus 不可用时，从 Java 传入的 fallback_chunks 中读取兜底数据。
同时提供 Milvus 可用性检查和降级提示信息（原 resilience.milvus_fallback 已并入此处）。
"""

import json
import logging
import re
from typing import Any

from .milvus_client import ping as milvus_ping
from .schema import ColumnInfo, RetrievedSchema, RetrieveResponse

logger = logging.getLogger(__name__)

# 降级时保留的 chunk 类型集合（模块级常量，避免每次调用重新创建）
_FALLBACK_CHUNK_TYPES = {
    "TABLE_DESC", "CORE_TABLE", "SCHEMA",
    "JOIN_PATH", "METRIC", "FIELD_NOTE", "QUERY_SCENE",
}


def is_milvus_available() -> bool:
    """检查 Milvus 是否可用（每次查询时调用，连接成功则自动恢复正常 RAG）"""
    return milvus_ping()


def get_degradation_notice() -> str:
    """获取降级提示信息"""
    return "知识库暂时不可用，已使用降级方案，召回精度可能降低"


def _first_non_none(d: dict, *keys) -> any:
    """从字典中按优先级取第一个非 None 的值（允许 0、空字符串等 falsy 值）。"""
    for key in keys:
        val = d.get(key)
        if val is not None:
            return val
    return None


def fallback_retrieve(
    datasource_id: int,
    fallback_chunks: list[dict] | None = None,
    *,
    active_snapshot_id: int | None = None,
    question: str = "",
    limit: int | None = None,
) -> RetrieveResponse:
    """降级检索：只使用 Java 传入的当前快照数据，并做确定性排序。"""
    logger.warning("RAG 降级触发 datasource_id=%d snapshot_id=%s", datasource_id, active_snapshot_id)

    candidates: list[tuple[float, dict]] = []
    for chunk in fallback_chunks or []:
        chunk_type = chunk.get("chunk_type") or chunk.get("chunkType")
        if chunk_type not in _FALLBACK_CHUNK_TYPES:
            continue

        snapshot_id = _first_non_none(
            chunk, "snapshot_id", "snapshotId", "metadata_snapshot_id", "metadataSnapshotId"
        )
        if active_snapshot_id is not None:
            # active snapshot 已明确时，缺少快照标识的数据也必须拒绝，
            # 否则旧数据或未绑定数据会绕过版本隔离进入上下文。
            if snapshot_id is None:
                continue
            try:
                if int(snapshot_id) != active_snapshot_id:
                    continue
            except (TypeError, ValueError):
                continue

        table_name = _first_non_none(
            chunk, "related_table", "relatedTable", "table_name", "tableName"
        ) or ""
        related_tables = _as_list(
            _first_non_none(chunk, "related_tables", "relatedTables")
        ) or ([table_name] if table_name else [])
        related_columns = _as_list(
            _first_non_none(
                chunk,
                "related_columns",
                "relatedColumns",
                "related_column",
                "relatedColumn",
            )
        )
        chunk_text = _first_non_none(chunk, "chunk_text", "chunkText") or ""
        candidates.append(
            (_question_match_score(question, table_name, related_tables, related_columns, chunk_text), chunk)
        )

    candidates.sort(key=lambda item: item[0], reverse=True)
    selected = candidates if limit is None else candidates[: max(1, limit)]
    results = [_to_retrieved_schema(match_score, chunk) for match_score, chunk in selected]

    return RetrieveResponse(
        results=results,
        total_found=len(candidates),
        returned=len(results),
        degraded=True,
        degrade_reason="Milvus 不可用，使用降级方案返回当前快照的知识信息",
        message="Milvus 不可用，使用降级方案返回当前快照的知识信息",
    )


def _to_retrieved_schema(match_score: float, chunk: dict) -> RetrievedSchema:
    table_name = _first_non_none(
        chunk, "related_table", "relatedTable", "table_name", "tableName"
    ) or ""
    related_columns = _as_list(
        _first_non_none(
            chunk,
            "related_columns",
            "relatedColumns",
            "related_column",
            "relatedColumn",
        )
    )
    return RetrievedSchema(
        table_name=table_name,
        columns=[ColumnInfo(name=column) for column in related_columns],
        score=min(0.8, 0.5 + match_score),
        relevance_score=min(0.8, 0.5 + match_score),
        chunk_type=chunk.get("chunk_type") or chunk.get("chunkType") or "",
        source_version=_first_non_none(chunk, "knowledge_version_no", "knowledgeVersionNo") or 0,
        snapshot_id=_first_non_none(
            chunk, "snapshot_id", "snapshotId", "metadata_snapshot_id", "metadataSnapshotId"
        ),
        doc_id=_first_non_none(chunk, "doc_id", "docId"),
        source_id=_first_non_none(chunk, "source_id", "sourceId"),
        chunk_index=_first_non_none(chunk, "chunk_index", "chunkIndex"),
        chunk_group_id=_first_non_none(chunk, "chunk_group_id", "chunkGroupId") or "",
        related_tables=_as_list(_first_non_none(chunk, "related_tables", "relatedTables"))
        or ([table_name] if table_name else []),
        related_columns=related_columns,
        entity_ids=_as_int_list(_first_non_none(chunk, "entity_ids", "entityIds")),
        trust_score=_first_non_none(chunk, "trust_score", "trustScore"),
        chunk_text=_first_non_none(chunk, "chunk_text", "chunkText") or "",
        governance_status=_first_non_none(chunk, "governance_status", "governanceStatus") or "",
        review_status=_first_non_none(chunk, "review_status", "reviewStatus") or "",
    )


def _as_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return []
        try:
            parsed = json.loads(text)
            if isinstance(parsed, list):
                return [str(item).strip() for item in parsed if str(item).strip()]
        except json.JSONDecodeError:
            pass
        return [item.strip() for item in text.split(",") if item.strip()]
    return [str(value)]


def _as_int_list(value: Any) -> list[int]:
    result: list[int] = []
    for item in _as_list(value):
        try:
            result.append(int(item))
        except (TypeError, ValueError):
            continue
    return result


def _question_match_score(
    question: str,
    table_name: str,
    related_tables: list[str],
    related_columns: list[str],
    chunk_text: str,
) -> float:
    """无额外 LLM 调用的 fallback 相关性排序。"""
    if not question:
        return 0.0
    haystack = " ".join([table_name, *related_tables, *related_columns, chunk_text]).lower()
    hints = _question_hints(question)
    if not hints:
        return 0.15 if question.lower() in haystack else 0.0
    matched = sum(1 for hint in set(hints) if hint in haystack)
    return min(0.25, matched * 0.05)


def _question_hints(question: str) -> list[str]:
    """提取 ASCII 标识符和中文双字片段，供无 LLM 的降级排序使用。"""
    normalized = question.lower()
    hints = re.findall(r"[a-zA-Z_][a-zA-Z0-9_]{1,}", normalized)
    for run in re.findall(r"[\u4e00-\u9fff]{2,}", normalized):
        hints.append(run)
        hints.extend(run[index:index + 2] for index in range(len(run) - 1))
    return list(dict.fromkeys(hints))
