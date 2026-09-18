"""IAM-SIMPLE-1 query orchestration.

This is intentionally a small independent execution path.  It does not call
the legacy Agent graph, legacy sandbox routes, or legacy permission fields.
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any

from dataocean.core.error_messages import sanitize_error
from dataocean.infra.llm import call_llm
from dataocean.sandbox.executor import execute as execute_sql

from .firewall import build_model_context, resource_index
from .schema import (
    S1ExecutionBinding,
    S1PermissionSnapshot,
    S1QueryExecuteRequest,
    S1RagRetrieveRequest,
    S1SqlExecuteRequest,
    S1SqlValidateRequest,
)
from .sql_security import S1SqlSecurityError, S1SqlValidation, inject_row_conditions, validate_sql

logger = logging.getLogger(__name__)


def _safe_error(message: str | None, bindings: list[S1ExecutionBinding]) -> str:
    """Remove execution binding values before an error can leave Python."""
    safe = str(message or "SQL 执行失败")
    for binding in bindings:
        value = binding.value
        values = value if isinstance(value, list) else [value]
        for item in values:
            if item is not None and str(item):
                safe = safe.replace(str(item), "?")
    return safe[:500]


def _schema_from_snapshot(snapshot: S1PermissionSnapshot) -> list[dict[str, Any]]:
    return [
        {
            "tableName": resource.tableName,
            "columns": [
                {
                    "name": column.name,
                    "type": column.dataType or "UNKNOWN",
                    "protectionLevel": column.protectionLevel,
                    "maskPolicy": column.maskPolicy,
                }
                for column in resource.columns
                if column.protectionLevel != "HIDDEN"
            ],
        }
        for resource in snapshot.resources
    ]


def _contract_matches(request: Any, snapshot: S1PermissionSnapshot) -> None:
    if request.protocolVersion != "IAM-SIMPLE-1":
        raise S1SqlSecurityError("未知 IAM-SIMPLE-1 协议")
    if (
        request.taskId != snapshot.taskId
        or request.userId != snapshot.userId
        or request.datasourceId != snapshot.datasourceId
        or request.activeMetadataSnapshotId != snapshot.activeMetadataSnapshotId
        or request.permissionRevision != snapshot.permissionRevision
    ):
        raise S1SqlSecurityError("S1 执行上下文身份不一致")
    if not snapshot.resources:
        raise S1SqlSecurityError("S1 快照资源为空")
    if any(not resource.columns for resource in snapshot.resources):
        raise S1SqlSecurityError("S1 快照字段为空")


def validate_request(request: S1SqlValidateRequest) -> S1SqlValidation:
    _contract_matches(request, request.permissionSnapshot)
    validation = validate_sql(request.sql, request.permissionSnapshot)
    if not validation.passed:
        return validation
    try:
        rewritten, _ = inject_row_conditions(
            validation.sql,
            request.permissionSnapshot,
            request.executionBindings,
        )
    except S1SqlSecurityError as exc:
        return S1SqlValidation(False, violations=[str(exc)])
    final_validation = validate_sql(rewritten, request.permissionSnapshot)
    if not final_validation.passed:
        return final_validation
    final_validation.sql = rewritten
    return final_validation


async def retrieve(request: S1RagRetrieveRequest) -> list[dict[str, Any]]:
    _contract_matches(request, request.permissionSnapshot)
    from .firewall import filter_chunks
    # S1 uses the existing Milvus embedding/search/adjacent expansion/rerank
    # pipeline, then applies the S1 table/column intersection once more before
    # anything reaches a model.  The supplied chunks are the fail-closed
    # fallback input for Milvus outages.
    try:
        from dataocean.rag.schema import RetrieveRequest
        from dataocean.rag.service import retrieve_schemas

        response = await retrieve_schemas(RetrieveRequest(
            datasourceId=request.datasourceId,
            question=request.question,
            topK=10,
            activeSnapshotId=request.activeMetadataSnapshotId,
            fallbackChunks=request.chunks,
        ))
        candidates: list[dict[str, Any]] = []
        for item in response.results:
            tables = list(getattr(item, "related_tables", []) or [])
            if not tables and getattr(item, "table_name", ""):
                tables = [item.table_name]
            columns = list(getattr(item, "related_columns", []) or [])
            if not columns and tables:
                columns = [f"{tables[0]}.{column.name}" for column in item.columns]
            candidates.append({
                "datasourceId": request.datasourceId,
                "activeMetadataSnapshotId": request.activeMetadataSnapshotId,
                "tables": tables,
                "columns": columns,
                "chunkText": getattr(item, "chunk_text", ""),
                "chunkType": getattr(item, "chunk_type", ""),
                "score": getattr(item, "score", 0),
                "docId": getattr(item, "doc_id", None),
                "versionNo": getattr(item, "source_version", 0),
            })
        filtered = filter_chunks(candidates, request.permissionSnapshot)
        if filtered:
            return filtered
    except Exception:
        # Retrieval failure is allowed to degrade only to the same filtered
        # fallback; it never sends an unfiltered vector result onward.
        pass
    return filter_chunks(request.chunks, request.permissionSnapshot)


async def execute_validated(request: S1SqlExecuteRequest) -> dict[str, Any]:
    _contract_matches(request, request.permissionSnapshot)
    original_validation = validate_sql(request.originalSql, request.permissionSnapshot)
    if not original_validation.passed:
        raise S1SqlSecurityError("执行 SQL 原文未通过 S1 AST 校验")
    rewritten, params = inject_row_conditions(
        original_validation.sql,
        request.permissionSnapshot,
        request.executionBindings,
    )
    if rewritten != request.validatedSql:
        raise S1SqlSecurityError("执行 SQL 与已校验并注入的 SQL 不一致")
    validation = validate_sql(request.validatedSql, request.permissionSnapshot)
    if not validation.passed:
        raise S1SqlSecurityError("执行 SQL 未通过同一份 S1 AST 校验")
    result = await execute_sql(
        sql=request.validatedSql,
        datasource_id=request.datasourceId,
        connection_config=request.connectionConfig.model_dump(),
        mask_columns={},
        task_id=request.taskId,
        parameters=params,
    )
    return {
        "success": result.success,
        "data": result.rows if result.success else [],
        "columns": result.columns if result.success else [],
        "rowCount": result.row_count,
        "executionTimeMs": result.execution_time_ms,
        "error": _safe_error(result.error, request.executionBindings) if result.error else None,
        "errorType": result.error_type or None,
        "trace": {
            "permissionRevision": request.permissionRevision,
            "activeMetadataSnapshotId": request.activeMetadataSnapshotId,
            "usedTables": validation.used_tables,
            "usedColumns": validation.used_columns,
            "sourceTrace": validation.source_trace,
            "maskedFields": validation.masked_fields,
        },
    }


async def run_query(request: S1QueryExecuteRequest) -> dict[str, Any]:
    _contract_matches(request, request.permissionSnapshot)
    start = time.time()
    safe_rag = await retrieve(S1RagRetrieveRequest(
        protocolVersion=request.protocolVersion,
        taskId=request.taskId,
        userId=request.userId,
        datasourceId=request.datasourceId,
        activeMetadataSnapshotId=request.activeMetadataSnapshotId,
        permissionRevision=request.permissionRevision,
        permissionSnapshot=request.permissionSnapshot,
        question=request.question,
        chunks=request.ragChunks + request.fallbackChunks,
    ))
    context = build_model_context(
        request.permissionSnapshot,
        _schema_from_snapshot(request.permissionSnapshot),
        safe_rag,
        request.glossaryTerms,
        request.fewShotExamples,
        [turn.model_dump() for turn in request.conversationHistory],
        request.conversationSummary,
        [binding.value for binding in request.executionBindings],
    )
    prompt = {
        "question": request.question,
        "schema": context["schema"],
        "rag": context["rag"],
        "glossary": context["glossary"],
        "fewShot": context["fewShot"],
        "history": context["conversationHistory"],
        "summary": context["conversationSummary"],
        # Explicitly no executionBindings here.
    }
    try:
        generated = await call_llm(
            system_prompt="你是 IAM-SIMPLE-1 安全问数 SQL 生成器。只生成 SELECT，不输出解释之外的权限信息。",
            user_prompt=json.dumps(prompt, ensure_ascii=False),
            temperature=0.1,
        )
        sql = generated.strip().replace("```sql", "").replace("```", "").strip()
        validation = validate_request(
            S1SqlValidateRequest(
                protocolVersion=request.protocolVersion,
                taskId=request.taskId,
                userId=request.userId,
                datasourceId=request.datasourceId,
                activeMetadataSnapshotId=request.activeMetadataSnapshotId,
                permissionRevision=request.permissionRevision,
                permissionSnapshot=request.permissionSnapshot,
                executionBindings=request.executionBindings,
                sql=sql,
            )
        )
        if not validation.passed:
            return {"taskId": request.taskId, "protocolVersion": request.protocolVersion, "status": "FAILED", "error": validation.violations[0]}
        execution = await execute_validated(
            S1SqlExecuteRequest(
                protocolVersion=request.protocolVersion,
                taskId=request.taskId,
                userId=request.userId,
                datasourceId=request.datasourceId,
                activeMetadataSnapshotId=request.activeMetadataSnapshotId,
                permissionRevision=request.permissionRevision,
                permissionSnapshot=request.permissionSnapshot,
                executionBindings=request.executionBindings,
                originalSql=sql,
                validatedSql=validation.sql,
                connectionConfig=request.connectionConfig,
            )
        )
        if not execution["success"]:
            return {"taskId": request.taskId, "protocolVersion": request.protocolVersion, "status": "FAILED", "error": execution["error"], "trace": execution["trace"]}
        return {
            "taskId": request.taskId,
            "protocolVersion": request.protocolVersion,
            "status": "COMPLETED",
            "sql": validation.sql,
            "sqlExplanation": "SQL 已通过 IAM-SIMPLE-1 当前快照校验与参数化执行保护",
            "data": execution["data"],
            "columns": execution["columns"],
            "rowCount": execution["rowCount"],
            "usedTables": validation.used_tables,
            "usedColumns": validation.used_columns,
            "sourceTrace": execution["trace"]["sourceTrace"],
            "maskedFields": validation.masked_fields,
            "chartConfig": None,
            "suggestedQuestions": [],
            "permissionRevision": request.permissionRevision,
            "activeMetadataSnapshotId": request.activeMetadataSnapshotId,
            "ragUsed": bool(context["rag"]),
            "degraded": not bool(context["rag"]),
            "degradeNotice": None if context["rag"] else "无可验证的 S1 知识上下文，已安全降级",
            "totalTimeMs": int((time.time() - start) * 1000),
        }
    except Exception as exc:  # sanitized, no binding/SQL value is returned
        return {
            "taskId": request.taskId,
            "protocolVersion": request.protocolVersion,
            "status": "FAILED",
            "error": sanitize_error(exc),
            "totalTimeMs": int((time.time() - start) * 1000),
        }
