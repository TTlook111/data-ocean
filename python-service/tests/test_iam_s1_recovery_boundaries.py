"""Read-only adversarial checks. No DB, Redis, service or provider calls."""
from __future__ import annotations

import time
import os
from types import SimpleNamespace
from unittest.mock import AsyncMock

os.environ["LANGCHAIN_TRACING_V2"] = "false"
os.environ["LANGSMITH_TRACING"] = "false"
os.environ["INTERNAL_TOKEN"] = "test-internal-token-0123456789abcdef"
os.environ["JAVA_GATEWAY_URL"] = "http://127.0.0.1:9"

import pytest
from langgraph.checkpoint.memory import InMemorySaver
from dataocean.iam_s1 import graph as g
from dataocean.iam_s1.schema import S1QueryExecuteRequest


def request():
    return S1QueryExecuteRequest.model_validate({
        "protocolVersion": "IAM-SIMPLE-1", "taskId": "independent-recovery",
        "userId": 7, "datasourceId": 701, "activeMetadataSnapshotId": 8801,
        "permissionRevision": 9, "conversationId": 47,
        "conversationThreadId": "iam-s1:7:701:47", "question": "count products",
        "conversationHistory": [], "conversationSummary": None, "ragChunks": [],
        "fallbackChunks": [], "glossaryTerms": [], "fewShotExamples": [],
        "candidateCatalog": {"datasourceId": 701, "activeMetadataSnapshotId": 8801,
                             "permissionRevision": 9, "tables": []},
        "deadlineEpochSeconds": time.time() + 60,
    })


def protected():
    return {"allowed": True, "success": True, "status": "PROTECTED",
            "attemptId": "attempt-1", "sqlHash": "a" * 64,
            "sql": "SELECT COUNT(*) AS total FROM products", "data": [{"total": 42}],
            "columns": [{"name": "total", "type": "BIGINT"}], "rowCount": 1,
            "usedTables": ["products"], "usedColumns": [], "maskedFields": {},
            "sourceTrace": [{"outputColumn": "total", "sources": [], "sourceKind": "NO_COLUMN_SOURCE"}]}


def executing_state():
    state = g._initial_state(request())
    state.update(attemptCount=1, currentAttemptId="attempt-1", currentSqlHash="a" * 64,
                 currentSql=protected()["sql"], usedTables=["products"], usedColumns=[],
                 columnUsages={}, sqlPrecheckError="")
    return state


@pytest.mark.asyncio
async def test_protected_reply_at_executing_gate_does_not_run_sql_again(monkeypatch):
    """A second worker authorized before the first one protected its result."""
    state = executing_state()
    monkeypatch.setattr(g, "_public_error", lambda exc: type(exc).__name__ + ": " + str(exc))
    snapshot = {"protocolVersion": "IAM-SIMPLE-1", "taskId": state["taskId"], "userId": 7,
                "datasourceId": 701, "activeMetadataSnapshotId": 8801, "permissionRevision": 9,
                "calculatedAt": "2026-10-28T00:00:00", "nextEffectiveAt": None, "resources": [],
                "capabilities": {"query": True, "viewSql": False, "export": False}}
    monkeypatch.setattr(g, "authorize_attempt", AsyncMock(return_value={
        "allowed": True, "status": "AUTHORIZED", "permissionSnapshot": snapshot,
        "executionBindings": [], "connectionConfig": {
            "host": "offline.invalid", "port": 3306, "database": "fixture",
            "username": "reader", "password": "synthetic-only"}}))
    monkeypatch.setattr(g, "validate_request", lambda _: SimpleNamespace(passed=True, sql=protected()["sql"]))
    monkeypatch.setattr(g, "mark_attempt_executing", AsyncMock(return_value=protected()))
    execute = AsyncMock(return_value={"success": True, "data": [{"total": 42}],
        "columns": protected()["columns"], "trace": {"usedTables": ["products"],
        "usedColumns": [], "sourceTrace": protected()["sourceTrace"]}})
    monkeypatch.setattr(g, "execute_validated", execute)
    monkeypatch.setattr(g, "protect_attempt_result", AsyncMock(return_value=protected()))
    result = await g._authorize_execute_protect_node(state)
    assert result["protectedResult"] == protected(), result.get("error")
    execute.assert_not_awaited()


@pytest.mark.asyncio
async def test_authorize_protected_reuses_result_without_sql(monkeypatch):
    monkeypatch.setattr(g, "authorize_attempt", AsyncMock(return_value=protected()))
    execute = AsyncMock()
    monkeypatch.setattr(g, "execute_validated", execute)
    result = await g._authorize_execute_protect_node(executing_state())
    assert result["protectedResult"] == protected()
    execute.assert_not_awaited()


class SimulatedProcessDeath(BaseException):
    pass


@pytest.mark.asyncio
async def test_java_protected_resume_supersedes_uncheckpointed_result_verifier(monkeypatch):
    """Actual LangGraph memory saver; crash after durable external model settlement.

    The Java budget reply models the already-settled result:1 call on replay.
    No real model, Java API, Redis or business database is contacted.
    """
    monkeypatch.setattr(g, "_compiled_graph", None)
    monkeypatch.setattr(g, "_checkpointer", InMemorySaver())
    async def retrieve(state):
        return {"safeRag": [], "ragRefreshCount": 1}
    async def plan(state):
        return {"rewrittenQuestion": state["question"], "linkedResources": [], "status": "PROCESSING"}
    async def generate(state):
        return {key: value for key, value in executing_state().items()
                if key in {"attemptCount", "currentAttemptId", "currentSqlHash", "currentSql",
                           "usedTables", "usedColumns", "columnUsages", "sqlPrecheckError"}}
    async def semantic(state):
        return {"semanticDecision": "MATCH"}
    async def authorize(state):
        return {"protectedResult": protected(), "status": "PROCESSING"}
    real_verify = g._verify_result_node
    crash = [True]
    async def verify(state):
        if crash[0]:
            # Process dies before the node result is saved to the checkpoint;
            # its external result:1 model settlement has already committed.
            raise SimulatedProcessDeath()
        return await real_verify(state)
    for name, fn in [("_retrieve_node", retrieve), ("_plan_node", plan),
                     ("_generate_sql_node", generate), ("_semantic_check_node", semantic),
                     ("_authorize_execute_protect_node", authorize), ("_verify_result_node", verify)]:
        monkeypatch.setattr(g, name, fn)
    req = request()
    with pytest.raises(SimulatedProcessDeath):
        await g._run_query_graph_once(req)
    persisted = await g._compiled_graph.aget_state({"configurable": {"thread_id": req.conversationThreadId}})
    assert persisted.next == ("verify_result",)
    assert persisted.values["protectedResult"]["status"] == "PROTECTED"
    crash[0] = False
    from dataocean.core.config import settings
    monkeypatch.setattr(settings, "qwen_model", "qwen-flash")
    monkeypatch.setattr(g, "resolve_scene", AsyncMock(return_value=SimpleNamespace(system="system", user="input")))
    monkeypatch.setattr(g, "_estimate_tokens", lambda _: 1)
    reserve = AsyncMock(return_value={"allowed": False, "error": "模型调用结果未能安全恢复，请重新提问"})
    provider = AsyncMock()
    monkeypatch.setattr(g, "reserve_model_call", reserve)
    monkeypatch.setattr(g, "call_llm_with_usage", provider)
    req.resumeProtectedResult = protected()
    req.llmCallsUsed = 4
    resumed = await g._run_query_graph_once(req, resume=True)
    provider.assert_not_awaited()
    assert resumed["status"] == "COMPLETED", resumed
    reserve.assert_not_awaited()
