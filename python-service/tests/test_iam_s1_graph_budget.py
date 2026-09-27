from __future__ import annotations

import time
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest

from dataocean.iam_s1 import graph as graph_module


@pytest.mark.asyncio
async def test_graph_model_calls_reserve_and_settle_durable_budget(monkeypatch):
    from dataocean.core.config import settings

    monkeypatch.setattr(settings, "qwen_model", "qwen-flash")
    state = {
        "taskId": "task-budget",
        "deadlineEpochSeconds": time.time() + 30,
        "llmCalls": 0,
        "estimatedCostCny": 0.0,
    }
    with patch("dataocean.iam_s1.graph.reserve_model_call", new=AsyncMock(return_value={
        "allowed": True, "callsUsed": 1, "estimatedCostCny": 0.003,
    })) as reserve, patch("dataocean.iam_s1.graph.settle_model_call", new=AsyncMock(return_value={
        "allowed": True, "callsUsed": 1, "estimatedCostCny": 0.0012,
    })) as settle, patch("dataocean.iam_s1.graph.call_llm_with_usage", new=AsyncMock(return_value=(
        '{"ok": true}', {"input_tokens": 100, "output_tokens": 20},
    ))) as llm:
        text, updated = await graph_module._budgeted_llm(
            state, "schema_linking", "system", "short test prompt", "plan:1", max_output_tokens=512,
        )

    assert text == '{"ok": true}'
    assert updated["llmCalls"] == 1
    assert updated["estimatedCostCny"] == 0.0012
    assert reserve.await_args.args == (
        "task-budget", "task-budget:plan:1", "schema_linking", graph_module._estimate_tokens("system\nshort test prompt"),
        512, "qwen-flash",
    )
    assert llm.await_args.kwargs["max_tokens"] == 512
    settle.assert_awaited_once()


@pytest.mark.asyncio
async def test_graph_refuses_call_nine_before_contacting_provider(monkeypatch):
    from dataocean.core.config import settings

    monkeypatch.setattr(settings, "qwen_model", "qwen-flash")
    state = {"taskId": "task-budget", "deadlineEpochSeconds": time.time() + 30,
             "llmCalls": 8, "estimatedCostCny": 0.02}
    with patch("dataocean.iam_s1.graph.call_llm_with_usage", new=AsyncMock()) as llm:
        with pytest.raises(graph_module.BudgetExceeded, match="调用上限"):
            await graph_module._budgeted_llm(state, "verify", "system", "prompt", "result:3")
        llm.assert_not_awaited()


@pytest.mark.asyncio
async def test_rag_embedding_uses_active_build_config_without_checkpointing_its_key():
    task_id = "task-rag-budget"
    state = {
        "taskId": task_id, "userId": 7, "datasourceId": 1,
        "activeMetadataSnapshotId": 88, "permissionRevision": 9,
        "conversationId": 42, "question": "统计订单", "rewrittenQuestion": "统计订单",
        "candidateCatalog": {
            "datasourceId": 1, "activeMetadataSnapshotId": 88, "permissionRevision": 9,
            "tables": [{"tableName": "orders", "tableComment": "订单", "governanceStatus": "NORMAL",
                        "columns": [{"columnMetaId": 1, "columnName": "id", "columnComment": None,
                                    "dataType": "BIGINT", "governanceStatus": "NORMAL",
                                    "protectionLevel": "NORMAL", "maskPolicy": None,
                                    "allowedUsages": ["PROJECTION"],
                                    "grantSources": [{"grantId": 1, "sourceSummary": "allow",
                                                      "grantSource": "MANUAL", "explicitColumns": ["id"]}]}]}],
        },
            "ragBuildId": "build-a", "ragSourceSnapshotId": 87,
            "ragCollectionName": "collection-a", "ragChunks": [],
            "ragRefreshCount": 0, "capabilities": {"query": True, "viewSql": False, "export": False},
            "deadlineEpochSeconds": time.time() + 30,
    }
    graph_module._runtime_secrets[task_id] = {
        "ragEmbeddingConfig": {"providerId": "dashscope", "model": "text-embedding-v4",
                               "dimension": 1024, "apiKey": "embedding-secret"},
    }
    with patch("dataocean.iam_s1.graph.reserve_model_call", new=AsyncMock(return_value={"allowed": True})), \
            patch("dataocean.iam_s1.graph.settle_model_call", new=AsyncMock(return_value={"allowed": True})), \
            patch("dataocean.iam_s1.graph.retrieve", new=AsyncMock(return_value=[])) as retrieve:
        result = await graph_module._retrieve_node(state)

    assert result["safeRag"] == []
    assert "embedding-secret" not in repr(result)
    assert retrieve.await_args.args[0].ragEmbeddingConfig["model"] == "text-embedding-v4"
    graph_module._runtime_secrets.pop(task_id, None)


@pytest.mark.asyncio
async def test_sql_execution_node_returns_only_java_protected_rows_to_graph_state():
    state = {
        "taskId": "task-safe-result", "userId": 7, "datasourceId": 1,
        "activeMetadataSnapshotId": 88, "permissionRevision": 9,
        "deadlineEpochSeconds": time.time() + 30, "attemptCount": 1,
        "currentSqlHash": "a" * 64, "currentAttemptId": "attempt-safe",
        "currentSql": "SELECT phone FROM users", "usedTables": ["users"],
        "usedColumns": ["users.phone"], "columnUsages": {"users.phone": ["PROJECTION"]},
        "sqlPrecheckError": "",
    }
    snapshot = {
        "protocolVersion": "IAM-SIMPLE-1", "taskId": "task-safe-result", "userId": 7,
        "datasourceId": 1, "activeMetadataSnapshotId": 88, "permissionRevision": 9,
        "calculatedAt": "2026-09-26T10:00:00", "nextEffectiveAt": None,
        "resources": [{
            "tableName": "users", "sourceSnapshotId": 88,
            "columns": [{
                "name": "phone", "columnId": 2, "usage": ["PROJECTION"],
                "protectionLevel": "MASKED", "maskPolicy": "PHONE", "dataType": "VARCHAR",
                "governanceStatus": "NORMAL", "sourceSnapshotId": 88,
            }],
            "grantSources": [],
        }],
        "capabilities": {"query": True, "viewSql": False, "export": False},
    }
    execution_raw = {
        "success": True,
        "data": [{"phone": "13800138000"}],
        "columns": [{"name": "phone", "type": "VARCHAR"}],
        "rowCount": 1,
        "executionTimeMs": 5,
        "trace": {"usedTables": ["users"], "usedColumns": ["users.phone"],
                  "sourceTrace": [{"outputColumn": "phone", "sources": ["users.phone"]}]},
    }
    protected = {
        "success": True, "status": "PROTECTED", "attemptId": "attempt-safe", "sqlHash": "a" * 64,
        "data": [{"phone": "138****8000"}], "columns": execution_raw["columns"], "rowCount": 1,
        "usedTables": ["users"], "usedColumns": ["users.phone"],
        "sourceTrace": execution_raw["trace"]["sourceTrace"], "maskedFields": {"phone": "PHONE"},
    }
    with patch("dataocean.iam_s1.graph.authorize_attempt", new=AsyncMock(return_value={
        "allowed": True, "status": "AUTHORIZED", "permissionSnapshot": snapshot,
        "executionBindings": [], "connectionConfig": {
            "host": "test", "port": 3306, "database": "fixture", "username": "reader", "password": "secret",
        },
    })), patch("dataocean.iam_s1.graph.mark_attempt_executing", new=AsyncMock(return_value={"allowed": True})), \
            patch("dataocean.iam_s1.graph.validate_request", return_value=SimpleNamespace(passed=True, sql="SELECT phone FROM users LIMIT 10000", violations=[])), \
            patch("dataocean.iam_s1.graph.execute_validated", new=AsyncMock(return_value=execution_raw)) as execute, \
            patch("dataocean.iam_s1.graph.protect_attempt_result", new=AsyncMock(return_value=protected)) as protect:
        result = await graph_module._authorize_execute_protect_node(state)

    assert result["protectedResult"]["data"] == [{"phone": "138****8000"}]
    assert "13800138000" not in repr(result)
    assert "secret" not in repr(result)
    execute.assert_awaited_once()
    protect.assert_awaited_once()


@pytest.mark.asyncio
async def test_sql_is_never_executed_when_java_reauthorization_denies_it():
    state = {
        "taskId": "task-denied", "userId": 7, "datasourceId": 1,
        "activeMetadataSnapshotId": 88, "permissionRevision": 9,
        "deadlineEpochSeconds": time.time() + 30, "attemptCount": 1,
        "currentAttemptId": "attempt-denied", "currentSqlHash": "b" * 64,
        "currentSql": "SELECT phone FROM users", "usedTables": ["users"],
        "usedColumns": ["users.phone"], "columnUsages": {"users.phone": ["PROJECTION"]},
        "sqlPrecheckError": "",
    }
    with patch("dataocean.iam_s1.graph.authorize_attempt", new=AsyncMock(return_value={
        "allowed": False, "status": "REJECTED", "error": "当前权限已撤销",
    })), patch("dataocean.iam_s1.graph.execute_validated", new=AsyncMock()) as execute:
        result = await graph_module._authorize_execute_protect_node(state)

    assert result["status"] == "FAILED"
    assert "protectedResult" not in result
    execute.assert_not_awaited()
