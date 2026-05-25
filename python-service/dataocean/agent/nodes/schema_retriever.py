"""Schema 召回节点

使用改写后的查询调用 RAG 模块进行语义检索，
获取相关表结构和字段上下文供 SQL 生成使用。
"""

from __future__ import annotations

import logging

from dataocean.rag.service import retrieve_schemas
from dataocean.rag.schema import RetrieveRequest

from ..state import AgentState

logger = logging.getLogger(__name__)


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

    try:
        request = RetrieveRequest(
            datasource_id=datasource_id,
            question=rewritten_query,
            top_k=10,
            active_snapshot_id=active_snapshot_id,
            confidence_scores=confidence_scores or None,
        )
        response = await retrieve_schemas(request)
    except Exception as e:
        logger.error("Schema 召回失败 task_id=%s error=%s", task_id, e, exc_info=True)
        return {
            **state,
            "schema_context": [],
            "error_message": f"Schema 召回失败：{e}",
            "current_node": "SCHEMA_RETRIEVER",
        }

    # 转换为 AgentState 中的 schema_context 格式
    schema_context = []
    for item in response.results:
        schema_context.append({
            "table_name": item.related_table or "",
            "chunk_type": item.chunk_type or "",
            "chunk_text": item.chunk_text or "",
            "related_column": item.related_column,
            "confidence_score": item.confidence_score if hasattr(item, "confidence_score") else 0,
            "governance_status": item.governance_status or "NORMAL",
            "score": item.score if hasattr(item, "score") else 0.0,
        })

    if not schema_context:
        return {
            **state,
            "schema_context": [],
            "error_message": "未找到相关数据表，请确认数据源已完成元数据治理和知识库发布",
            "current_node": "SCHEMA_RETRIEVER",
        }

    logger.info("Schema 召回完成 task_id=%s count=%d", task_id, len(schema_context))

    return {
        **state,
        "schema_context": schema_context,
        "current_node": "SCHEMA_RETRIEVER",
    }
