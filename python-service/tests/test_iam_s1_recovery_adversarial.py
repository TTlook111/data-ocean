"""Independent post-fix boundary cases; no live dependencies or providers."""
from __future__ import annotations

import copy
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from test_iam_s1_recovery_boundaries import g, request, protected, executing_state
from langgraph.checkpoint.memory import InMemorySaver


def install_execution_gate(monkeypatch, receipt):
    state = executing_state()
    snapshot = {"protocolVersion": "IAM-SIMPLE-1", "taskId": state["taskId"], "userId": 7,
                "datasourceId": 701, "activeMetadataSnapshotId": 8801, "permissionRevision": 9,
                "calculatedAt": "2026-10-28T00:00:00", "nextEffectiveAt": None, "resources": [],
                "capabilities": {"query": True, "viewSql": False, "export": False}}
    monkeypatch.setattr(g, "authorize_attempt", AsyncMock(return_value={
        "allowed": True, "status": "AUTHORIZED", "permissionSnapshot": snapshot,
        "executionBindings": [], "connectionConfig": {"host": "offline.invalid", "port": 3306,
            "database": "fixture", "username": "reader", "password": "synthetic-only"}}))
    monkeypatch.setattr(g, "validate_request", lambda _: SimpleNamespace(passed=True, sql=protected()["sql"]))
    monkeypatch.setattr(g, "mark_attempt_executing", AsyncMock(return_value=receipt))
    sql = AsyncMock(return_value={"success": True, "data": protected()["data"],
        "columns": protected()["columns"], "trace": {"usedTables": ["products"],
        "usedColumns": [], "sourceTrace": protected()["sourceTrace"]}})
    monkeypatch.setattr(g, "execute_validated", sql)
    monkeypatch.setattr(g, "protect_attempt_result", AsyncMock(return_value=protected()))
    return state, sql


@pytest.mark.asyncio
@pytest.mark.parametrize("field,value", [("attemptId", "attempt-other"), ("sqlHash", "b" * 64)])
async def test_executing_protected_with_wrong_identity_stops(monkeypatch, field, value):
    receipt = protected()
    receipt[field] = value
    state, sql = install_execution_gate(monkeypatch, receipt)
    result = await g._authorize_execute_protect_node(state)
    assert result["status"] == "FAILED"
    sql.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [None, "", "AUTHORIZED", "PROCESSING", "UNCERTAIN", "COMPLETED", "unknown"])
async def test_only_explicit_executing_status_allows_sql(monkeypatch, status):
    state, sql = install_execution_gate(monkeypatch, {"allowed": True, "status": status})
    result = await g._authorize_execute_protect_node(state)
    assert result["status"] == "FAILED"
    sql.assert_not_awaited()


@pytest.mark.asyncio
async def test_explicit_executing_can_still_run_once(monkeypatch):
    state, sql = install_execution_gate(monkeypatch, {"allowed": True, "status": "EXECUTING"})
    result = await g._authorize_execute_protect_node(state)
    assert result["protectedResult"]["attemptId"] == "attempt-1"
    sql.assert_awaited_once()


class CheckpointGraph:
    """Only transport of checkpoint is fake; production verify enforces deadline."""
    def __init__(self, values):
        self.values = copy.deepcopy(values)
        self.next = ("verify_result",)
        self.updates = []
        self.stream_calls = 0

    async def aget_state(self, config):
        return SimpleNamespace(values=self.values, next=self.next)

    async def aupdate_state(self, config, values, as_node):
        self.updates.append((copy.deepcopy(values), as_node))
        self.values.update(values)

    async def astream(self, data, config, **kwargs):
        self.stream_calls += 1
        assert data is None
        self.values = await g._verify_result_node(self.values)
        self.values["finalResult"] = {"taskId": self.values["taskId"],
            "protocolVersion": "IAM-SIMPLE-1", "status": self.values["status"]}
        yield {}


def install_checkpoint(monkeypatch, state):
    graph = CheckpointGraph(state)
    monkeypatch.setattr(g, "_checkpointer", object())
    monkeypatch.setattr(g, "_graph", AsyncMock(return_value=graph))
    provider = AsyncMock()
    reserve = AsyncMock()
    sql = AsyncMock()
    monkeypatch.setattr(g, "call_llm_with_usage", provider)
    monkeypatch.setattr(g, "reserve_model_call", reserve)
    monkeypatch.setattr(g, "execute_validated", sql)
    return graph, provider, reserve, sql


@pytest.mark.asyncio
@pytest.mark.parametrize("field,value", [("attemptId", "attempt-other"), ("sqlHash", "b" * 64)])
async def test_checkpoint_resume_wrong_receipt_identity_stops_before_update(monkeypatch, field, value):
    graph, provider, reserve, sql = install_checkpoint(monkeypatch, executing_state())
    req = request()
    req.resumeProtectedResult = {**protected(), field: value}
    result = await g._run_query_graph_once(req, resume=True)
    assert result["status"] == "FAILED"
    assert graph.updates == [] and graph.stream_calls == 0
    provider.assert_not_awaited()
    reserve.assert_not_awaited()
    sql.assert_not_awaited()


@pytest.mark.asyncio
async def test_later_sql_attempt_checkpoint_cannot_be_rolled_back_by_old_protection(monkeypatch):
    state = executing_state()
    state.update(attemptCount=2, currentAttemptId="attempt-2", currentSqlHash="b" * 64,
                 currentSql="SELECT SUM(price) FROM products")
    graph, provider, reserve, sql = install_checkpoint(monkeypatch, state)
    req = request()
    req.resumeProtectedResult = protected()
    result = await g._run_query_graph_once(req, resume=True)
    assert result["status"] == "FAILED"
    assert graph.updates == [] and graph.stream_calls == 0
    assert graph.values["currentAttemptId"] == "attempt-2"
    provider.assert_not_awaited()
    reserve.assert_not_awaited()
    sql.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("stored_llm,java_llm,stored_sql,java_sql", [(7, 2, 3, 1), (1, 8, 1, 3)])
@pytest.mark.parametrize("old_seconds,new_seconds", [(10, 50), (50, 10)])
async def test_recovery_budget_counts_never_drop_and_deadline_never_extends(monkeypatch,
        stored_llm, java_llm, stored_sql, java_sql, old_seconds, new_seconds):
    state = executing_state()
    old_deadline = time.time() + old_seconds
    new_deadline = time.time() + new_seconds
    state.update(llmCalls=stored_llm, attemptCount=stored_sql, deadlineEpochSeconds=old_deadline,
                 estimatedCostCny=0.07)
    graph, provider, reserve, sql = install_checkpoint(monkeypatch, state)
    req = request()
    req.resumeProtectedResult = protected()
    req.deadlineEpochSeconds = new_deadline
    req.llmCallsUsed = java_llm
    req.sqlAttemptsUsed = java_sql
    result = await g._run_query_graph_once(req, resume=True)
    assert result["status"] == "COMPLETED", result
    saved, as_node = graph.updates[0]
    assert as_node == "authorize_execute_protect"
    assert saved["llmCalls"] == max(stored_llm, java_llm)
    assert saved["attemptCount"] == max(stored_sql, java_sql)
    assert saved["deadlineEpochSeconds"] == min(old_deadline, new_deadline)
    assert saved["estimatedCostCny"] == 0.07
    assert saved["recoveredProtectedResult"] is True
    provider.assert_not_awaited()
    reserve.assert_not_awaited()
    sql.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("old_offset,new_offset", [(-1, 50), (50, -1)])
async def test_expired_original_or_java_deadline_cannot_complete_recovery(monkeypatch, old_offset, new_offset):
    state = executing_state()
    state["deadlineEpochSeconds"] = time.time() + old_offset
    graph, provider, reserve, sql = install_checkpoint(monkeypatch, state)
    req = request()
    req.resumeProtectedResult = protected()
    req.deadlineEpochSeconds = time.time() + new_offset
    result = await g._run_query_graph_once(req, resume=True)
    assert result["status"] == "TIMEOUT", result
    provider.assert_not_awaited()
    reserve.assert_not_awaited()
    sql.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("changes", [
    {"data": {}}, {"columns": []}, {"rowCount": 2}, {"rowCount": True},
    {"columns": "not-a-schema"}, {"data": [42]}, {"sourceTrace": []},
])
async def test_incomplete_protected_receipt_is_not_promoted(monkeypatch, changes):
    graph, provider, reserve, sql = install_checkpoint(monkeypatch, executing_state())
    req = request()
    req.resumeProtectedResult = {**protected(), **changes}
    result = await g._run_query_graph_once(req, resume=True)
    assert result["status"] == "FAILED", (changes, result)
    assert graph.updates == [] and graph.stream_calls == 0
    provider.assert_not_awaited()
    reserve.assert_not_awaited()
    sql.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("changes", [{"columns": "not-a-schema"}, {"data": [42]}, {"sourceTrace": []}])
async def test_real_graph_rejects_malformed_protected_recovery(monkeypatch, changes):
    monkeypatch.setattr(g, "_compiled_graph", None)
    monkeypatch.setattr(g, "_checkpointer", InMemorySaver())
    provider, reserve, sql = AsyncMock(), AsyncMock(), AsyncMock()
    monkeypatch.setattr(g, "call_llm_with_usage", provider)
    monkeypatch.setattr(g, "reserve_model_call", reserve)
    monkeypatch.setattr(g, "execute_validated", sql)
    req = request()
    state = executing_state()
    state["protectedResult"] = protected()
    graph = await g._graph()
    await graph.aupdate_state({"configurable": {"thread_id": req.conversationThreadId}},
                             state, as_node="authorize_execute_protect")
    req.resumeProtectedResult = {**protected(), **changes}
    result = await g._run_query_graph_once(req, resume=True)
    assert result["status"] == "FAILED", (changes, result)
    provider.assert_not_awaited()
    reserve.assert_not_awaited()
    sql.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("gate", ["authorize", "executing"])
async def test_reusing_existing_protection_does_not_enter_result_model_budget_again(monkeypatch, gate):
    state, sql = install_execution_gate(monkeypatch, protected())
    if gate == "authorize":
        monkeypatch.setattr(g, "authorize_attempt", AsyncMock(return_value=protected()))
    budgeted_model = AsyncMock(return_value=(
        '{"decision":"ANSWER","answer":"42"}', {**state, "llmCalls": 1}))
    monkeypatch.setattr(g, "_budgeted_llm", budgeted_model)
    protected_state = await g._authorize_execute_protect_node(state)
    result = await g._verify_result_node(protected_state)
    assert result["status"] == "COMPLETED", result
    sql.assert_not_awaited()
    budgeted_model.assert_not_awaited()
