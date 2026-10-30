"""Opt-in LangGraph recovery test using only the isolated Redis 8 acceptance store."""

from __future__ import annotations

import os
import uuid

import pytest


REDIS_URL = os.getenv("LANGGRAPH_TEST_REDIS_URL")
pytestmark = pytest.mark.skipif(
    not REDIS_URL,
    reason="Set LANGGRAPH_TEST_REDIS_URL only for the dedicated Redis 8 acceptance container.",
)


@pytest.mark.asyncio
async def test_graph_checkpoint_recovers_safe_state_without_credentials_or_bindings(monkeypatch):
    from dataocean.core.config import settings
    from dataocean.iam_s1 import graph as graph_module
    from dataocean.iam_s1.schema import S1QueryExecuteRequest, S1CandidateCatalog

    conversation_id = int(uuid.uuid4().hex[:8], 16)
    thread_id = f"iam-s1:7:701:{conversation_id}"
    task_id = f"task-{uuid.uuid4().hex}"
    request = S1QueryExecuteRequest.model_validate({
        "protocolVersion": "IAM-SIMPLE-1",
        "taskId": task_id,
        "userId": 7,
        "datasourceId": 701,
        "activeMetadataSnapshotId": 8801,
        "permissionRevision": 9,
        "conversationId": conversation_id,
        "conversationThreadId": thread_id,
        "candidateCatalog": {
            "datasourceId": 701,
            "activeMetadataSnapshotId": 8801,
            "permissionRevision": 9,
            "tables": [{
                "tableName": "products",
                "tableComment": "商品测试表",
                "governanceStatus": "NORMAL",
                "columns": [{
                    "columnMetaId": 1,
                    "columnName": "product_id",
                    "columnComment": "商品 ID",
                    "dataType": "BIGINT",
                    "governanceStatus": "NORMAL",
                    "protectionLevel": "NORMAL",
                    "maskPolicy": None,
                    "allowedUsages": ["PROJECTION", "FILTER", "JOIN"],
                    "grantSources": [{
                        "grantId": 5,
                        "subjectType": "USER",
                        "subjectId": 7,
                        "sourceSummary": "用户个人授权",
                        "departmentScope": None,
                        "grantSource": "MANUAL",
                        "sourceReferenceId": None,
                        "validFrom": "2026-09-26T00:00:00",
                        "validUntil": None,
                        "explicitColumns": ["product_id"],
                        "rowCondition": None,
                    }],
                }],
            }],
        },
        "question": "统计商品数",
        "ragEmbeddingConfig": {
            "providerId": "test-provider", "model": "text-embedding-v4",
            "dimension": 1024, "apiKey": "raw-embedding-secret-must-not-be-checkpointed",
        },
        "connectionConfig": {
            "host": "test-only.invalid", "port": 3306, "database": "fixture",
            "username": "test-reader", "password": "raw-connection-secret-must-not-be-checkpointed",
        },
        "conversationHistory": [],
        "conversationSummary": None,
        "ragChunks": [],
        "fallbackChunks": [],
        "glossaryTerms": [],
        "fewShotExamples": [],
    })

    async def retrieve(state):
        return {"safeRag": [], "ragRefreshCount": state.get("ragRefreshCount", 0) + 1}

    async def plan(state):
        return {"rewrittenQuestion": state["question"], "questionIntent": {"measure": "商品数"},
                "linkedResources": [{"tableName": "products", "columns": ["product_id"]}],
                "linkedSchema": [{"tableName": "products", "columns": [{"columnName": "product_id"}]}],
                "status": "PROCESSING"}

    async def generate(state):
        return {"attemptCount": 1, "currentAttemptId": "attempt-1", "currentSqlHash": "a" * 64,
                "currentSql": "SELECT COUNT(*) AS total FROM products", "usedTables": ["products"],
                "usedColumns": [], "columnUsages": {}, "status": "PROCESSING"}

    async def semantic(state):
        return {"semanticDecision": "MATCH", "semanticReason": "结构与问题一致"}

    async def execute_and_protect(state):
        return {"protectedResult": {
            "status": "PROTECTED", "attemptId": "attempt-1", "sqlHash": "a" * 64,
            "data": [{"total": 42}], "columns": [{"name": "total", "type": "BIGINT"}],
            "rowCount": 1, "usedTables": ["products"], "usedColumns": [],
            "sourceTrace": [{"outputColumn": "total", "sources": [], "sourceKind": "NO_COLUMN_SOURCE"}],
            "maskedFields": {},
        }}

    async def verify(state):
        return {"answer": "共有 42 种商品。", "status": "COMPLETED"}

    monkeypatch.setattr(settings, "langgraph_checkpoint_redis_url", REDIS_URL)
    monkeypatch.setattr(graph_module, "_compiled_graph", None)
    monkeypatch.setattr(graph_module, "_retrieve_node", retrieve)
    monkeypatch.setattr(graph_module, "_plan_node", plan)
    monkeypatch.setattr(graph_module, "_generate_sql_node", generate)
    monkeypatch.setattr(graph_module, "_semantic_check_node", semantic)
    monkeypatch.setattr(graph_module, "_authorize_execute_protect_node", execute_and_protect)
    monkeypatch.setattr(graph_module, "_verify_result_node", verify)

    await graph_module.initialize_checkpointer()
    try:
        first = await graph_module.run_query_graph(request)
        assert first["status"] == "COMPLETED", first
        assert first["data"] == [{"total": 42}]

        persisted = await graph_module._compiled_graph.aget_state({"configurable": {"thread_id": thread_id}})
        serialized = repr(persisted.values)
        assert "raw-connection-secret-must-not-be-checkpointed" not in serialized
        assert "raw-embedding-secret-must-not-be-checkpointed" not in serialized
        assert "executionBindings" not in serialized
        assert persisted.values["protectedResult"]["data"] == [{"total": 42}]
    finally:
        await graph_module.close_checkpointer()

    monkeypatch.setattr(graph_module, "_compiled_graph", None)
    await graph_module.initialize_checkpointer()
    try:
        recovered = await graph_module.run_query_graph(request, resume=True)
        assert recovered == first
    finally:
        await graph_module.close_checkpointer()
