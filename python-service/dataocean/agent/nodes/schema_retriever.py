"""Schema 召回节点

使用改写后的查询调用 RAG 模块进行语义检索，
获取相关表结构和字段上下文供 SQL 生成使用。
Milvus 不可用时自动降级，使用 skills.md 核心表。

Phase 3: 查询 Java 内部 API 获取表间关系（FOREIGN_KEY / LINEAGE / DERIVED_FROM），
增强 Schema Linking 阶段的 JOIN 推荐和字段解释能力。
"""

from __future__ import annotations

import logging
import os

import httpx

from dataocean.rag.service import retrieve_schemas
from dataocean.rag.schema import RetrieveRequest
from dataocean.rag.fallback import get_degradation_notice

from ..state import AgentState

logger = logging.getLogger(__name__)

JAVA_BASE_URL = os.getenv("JAVA_GATEWAY_URL", "http://localhost:8080")
INTERNAL_TOKEN = os.getenv("INTERNAL_TOKEN", "dataocean-internal-default")


async def run_schema_retriever(state: AgentState) -> AgentState:
    """执行 Schema 召回：使用改写后的查询调用 RAG 检索"""
    rewritten_query = state.get("rewritten_query", "")
    datasource_id = state.get("datasource_id", 0)
    active_snapshot_id = state.get("active_snapshot_id", 0)
    confidence_scores = state.get("confidence_scores", {})
    task_id = state.get("task_id", "")

    logger.info(
        "Schema 召回 task_id=%s datasource_id=%d query=%s",
        task_id, datasource_id, rewritten_query[:50],
    )

    fallback_chunks = state.get("fallback_chunks")
    try:
        request = RetrieveRequest(
            datasource_id=datasource_id,
            question=rewritten_query,
            top_k=10,
            active_snapshot_id=active_snapshot_id,
            confidence_scores=confidence_scores or None,
            fallback_chunks=fallback_chunks,
        )
        response = await retrieve_schemas(request)
    except Exception as e:
        logger.error("Schema 召回失败 task_id=%s error=%s", task_id, e, exc_info=True)
        return {
            "schema_context": [],
            "error_message": f"Schema 召回失败：{e}",
            "current_node": "SCHEMA_RETRIEVER",
        }

    # 转换为 AgentState 中的 schema_context 格式
    schema_context = []
    for item in response.results:
        columns_data = []
        if hasattr(item, "columns") and item.columns:
            columns_data = [
                {"name": c.name, "type": c.type or "", "comment": c.comment or "", "trust_score": c.trust_score}
                for c in item.columns
            ]
        table_comment = getattr(item, "table_comment", "") or ""
        source_type = getattr(item, "source_type", "SCHEMA") or "SCHEMA"
        schema_context.append({
            "table_name": item.table_name or "",
            "table_comment": table_comment,
            "source_type": source_type,
            "chunk_type": item.chunk_type or "",
            "chunk_text": item.chunk_text or "",
            "related_column": getattr(item, "related_column", None),
            "columns": columns_data,
            "confidence_score": getattr(item, "trust_score", 0) or 0,
            "governance_status": item.governance_status or "NORMAL",
            "score": item.score if hasattr(item, "score") else 0.0,
            "entity_id": getattr(item, "entity_id", None),  # Phase 3: 实体ID
        })

    if not schema_context:
        return {
            "schema_context": [],
            "error_message": "未找到相关数据表",
            "current_node": "SCHEMA_RETRIEVER",
        }

    # Phase 3: 查询关系数据增强 Schema
    schema_context = await _enrich_with_relationships(schema_context, task_id)

    logger.info("Schema 召回完成 task_id=%s count=%d degraded=%s", task_id, len(schema_context), response.degraded)

    result: dict = {"schema_context": schema_context, "current_node": "SCHEMA_RETRIEVER"}

    if response.degraded:
        result["degraded"] = True
        result["degrade_notice"] = get_degradation_notice()

    return result


# ---------------------------------------------------------------------------
# Phase 3: 关系数据加载（FOREIGN_KEY / LINEAGE / DERIVED_FROM）
# ---------------------------------------------------------------------------

async def _enrich_with_relationships(
    schema_context: list[dict],
    task_id: str = "",
) -> list[dict]:
    """为每个有 entity_id 的 schema 条目查询 Java 内部 API 获取实体间关系。

    失败时静默降级，不影响主流程。
    """
    enriched = []
    for item in schema_context:
        entity_id = item.get("entity_id")
        if not entity_id:
            enriched.append(item)
            continue

        relationships = await _fetch_relationships(entity_id)
        if not relationships:
            enriched.append(item)
            continue

        # 按类型分类关系
        foreign_keys = [r for r in relationships if r.get("relationType") == "FOREIGN_KEY"]
        lineages = [r for r in relationships if r.get("relationType") == "LINEAGE"]
        derived_froms = [r for r in relationships if r.get("relationType") == "DERIVED_FROM"]

        item = dict(item)
        item["relationships"] = relationships
        item["foreign_keys"] = foreign_keys
        item["lineages"] = lineages
        item["derived_froms"] = derived_froms

        enriched.append(item)

    total = sum(len(item.get("relationships", [])) for item in enriched)
    if total > 0:
        logger.info("Phase 3: 关系数据加载完成 task_id=%s total_relationships=%d", task_id, total)

    return enriched


async def _fetch_relationships(entity_id: int) -> list[dict]:
    """调用 Java GET /internal/metadata/entities/{id}/relationships。

    Args:
        entity_id: metadata_entity 主键 ID
    Returns:
        关系列表，失败时返回空列表（静默降级）
    """
    try:
        url = f"{JAVA_BASE_URL}/internal/metadata/entities/{entity_id}/relationships"
        params = {"relationType": "FOREIGN_KEY,LINEAGE,DERIVED_FROM"}
        headers = {"X-Internal-Token": INTERNAL_TOKEN}
        async with httpx.AsyncClient(timeout=3.0) as client:
            response = await client.get(url, params=params, headers=headers)
            response.raise_for_status()
            data = response.json()
            if data.get("code") == 200:
                return data.get("data", [])
            return []
    except Exception as e:
        logger.debug("关系数据查询失败 entity_id=%s error=%s", entity_id, e)
        return []
