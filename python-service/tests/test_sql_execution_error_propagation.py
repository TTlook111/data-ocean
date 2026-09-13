"""SQL 执行失败结果传播回归测试。

覆盖 executor 与 graph 的边界：失败结果不能因自校正或重试路由而丢失，
终态错误也不能被误判为成功。
"""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from langgraph.graph import END

from dataocean.agent import graph as agent_graph
from dataocean.agent.nodes.sql_executor import _extract_column_derivations, run_sql_executor
from dataocean.agent.nodes.schema_linker import _build_schema_summary
from dataocean.agent.state import AgentState


def _base_state() -> AgentState:
    return {
        "task_id": "sql-error-task",
        "question": "查询订单",
        "datasource_id": 1,
        "user_id": 1,
        "generated_sql": "SELECT missing_column FROM orders",
        "validation_result": {
            "valid": True,
            "rewritten_sql": "SELECT missing_column FROM orders",
        },
        "connection_config": {
            "host": "localhost",
            "port": 3306,
            "database": "test_db",
            "username": "readonly",
            "password": "test",
        },
        "retry_count": 0,
        "error_message": "",
        "execution_result": {},
    }


def _failed_result(error: str, error_type: str = "SYNTAX") -> MagicMock:
    return MagicMock(
        success=False,
        columns=[],
        rows=[],
        row_count=0,
        execution_time_ms=17,
        error=error,
        error_type=error_type,
    )


def _successful_result() -> MagicMock:
    return MagicMock(
        success=True,
        columns=[{"name": "id", "type": "INT"}],
        rows=[{"id": 1}],
        row_count=1,
        execution_time_ms=21,
        error=None,
    )


@pytest.mark.asyncio
async def test_first_correctable_failure_keeps_execution_error():
    """首次可修正失败即使生成了修正版 SQL，也必须保留本次错误。"""
    state = _base_state()
    failure = _failed_result("Unknown column 'missing_column'")

    with patch("dataocean.sandbox.executor.execute", new_callable=AsyncMock, return_value=failure), \
         patch("dataocean.infra.llm.call_llm", new_callable=AsyncMock,
               return_value="SELECT id FROM orders"), \
         patch("dataocean.agent.nodes.sql_executor.sse.emit_progress", new_callable=AsyncMock):
        result = await run_sql_executor(state)

    assert result["execution_result"]["error"] == "Unknown column 'missing_column'"
    assert result["retry_count"] == 1
    assert result["generated_sql"] == "SELECT id FROM orders"
    assert agent_graph.after_executor({**state, **result}) == "sql_generator"


@pytest.mark.asyncio
async def test_retry_success_clears_previous_execution_error():
    """一次失败后的重试成功必须覆盖旧失败结果，并保留 retry_count。"""
    state = _base_state()
    failure = _failed_result("Unknown column 'missing_column'")

    with patch("dataocean.sandbox.executor.execute", new_callable=AsyncMock) as mock_execute, \
         patch("dataocean.infra.llm.call_llm", new_callable=AsyncMock,
               return_value="SELECT id FROM orders"), \
         patch("dataocean.agent.nodes.sql_executor.sse.emit_progress", new_callable=AsyncMock):
        mock_execute.side_effect = [failure, _successful_result()]

        first_patch = await run_sql_executor(state)
        retry_state = {
            **state,
            **first_patch,
            # 模拟 SQL generator 在下一轮成功后清除上一轮提示。
            "error_message": "",
            "validation_result": {
                "valid": True,
                "rewritten_sql": first_patch["generated_sql"],
            },
        }
        assert agent_graph.after_executor(retry_state) == "sql_generator"

        success_patch = await run_sql_executor(retry_state)

    final_state = {**retry_state, **success_patch}
    assert final_state["execution_result"]["error"] is None
    assert final_state["retry_count"] == 1
    assert final_state["error_message"] == ""
    assert agent_graph.after_executor(final_state) == "data_visualizer"


@pytest.mark.asyncio
async def test_retry_exhausted_keeps_last_execution_error_and_count():
    """达到最大重试次数后保留最后一次错误，且不再路由到生成器。"""
    state = _base_state()
    error = "Unknown column 'missing_column'"

    with patch("dataocean.sandbox.executor.execute", new_callable=AsyncMock,
               side_effect=[_failed_result(error) for _ in range(3)]), \
         patch("dataocean.infra.llm.call_llm", new_callable=AsyncMock,
               return_value="SELECT id FROM orders") as mock_correction, \
         patch("dataocean.agent.nodes.sql_executor.sse.emit_progress", new_callable=AsyncMock):
        current_state = state
        for _ in range(3):
            current_patch = await run_sql_executor(current_state)
            current_state = {
                **current_state,
                **current_patch,
                "validation_result": {
                    "valid": True,
                    "rewritten_sql": current_patch.get("generated_sql", current_state["generated_sql"]),
                },
            }

    assert current_state["execution_result"]["error"] == error
    assert current_state["retry_count"] == 3
    assert agent_graph.after_executor(current_state) == END
    # retry_count == max_retries 时不再调用无效的自校正。
    assert mock_correction.await_count == 2


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("error", "error_type"),
    [
        ("查询超时（30s），已自动终止", "TIMEOUT"),
        ("查询已取消", "CANCELLED"),
        ("数据库连接失败", "CONNECTION"),
    ],
)
async def test_non_retryable_execution_failure_is_preserved(error: str, error_type: str):
    """超时、取消和连接错误应保留错误且不触发重试。"""
    state = _base_state()
    with patch("dataocean.sandbox.executor.execute", new_callable=AsyncMock,
               return_value=_failed_result(error, error_type)), \
         patch("dataocean.infra.llm.call_llm", new_callable=AsyncMock) as mock_correction, \
         patch("dataocean.agent.nodes.sql_executor.sse.emit_progress", new_callable=AsyncMock):
        result = await run_sql_executor(state)

    final_state = {**state, **result}
    assert final_state["execution_result"]["error"] == error
    assert final_state["execution_result"]["error_type"] == error_type
    assert final_state["retry_count"] == 1
    assert mock_correction.await_count == 0
    assert agent_graph.after_executor(final_state) == END


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("abort_message", "error_type"),
    [("查询已取消", "CANCELLED"), ("处理时间超出限制，请简化问题", "TIMEOUT")],
)
async def test_executor_abort_before_call_keeps_execution_error(abort_message: str, error_type: str):
    """节点入口取消或预算耗尽时，也要产生可路由的 execution_result。"""
    state = _base_state()
    with patch("dataocean.agent.graph._check_cancelled_or_timeout", return_value=abort_message), \
         patch("dataocean.agent.graph.sse.emit_progress", new_callable=AsyncMock):
        result = await agent_graph.sql_executor_node(state)

    assert result["execution_result"]["error"] == abort_message
    assert result["execution_result"]["error_type"] == error_type
    assert result["retry_count"] == 0
    assert agent_graph.after_executor(result) == END


@pytest.mark.asyncio
async def test_sandbox_exception_is_converted_to_execution_error():
    """沙箱异常而非结构化失败返回时，同样不能由 wrapper 丢掉执行错误。"""
    state = _base_state()
    with patch("dataocean.sandbox.executor.execute", new_callable=AsyncMock,
               side_effect=RuntimeError("database connection lost")), \
         patch("dataocean.agent.nodes.sql_executor.sse.emit_progress", new_callable=AsyncMock):
        result = await run_sql_executor(state)

    assert result["execution_result"]["error"] != ""
    assert result["retry_count"] == 1


@pytest.mark.asyncio
async def test_parallel_metadata_prefetch_does_not_conflict_on_current_node():
    """并行预取节点不应与 Rewriter 同时写入 last-value current_node。"""
    with patch("dataocean.agent.graph.sse.emit_progress", new_callable=AsyncMock):
        result = await agent_graph.metadata_prefetch_node({"task_id": "parallel-task", "retry_count": 0})

    assert "current_node" not in result


def test_column_derivations_use_agent_state_field_names():
    """AgentState 到 QueryResult 的列级血缘字段必须使用 snake_case。"""
    derivations = _extract_column_derivations("SELECT SUM(total_amount) AS total FROM demo_order")

    assert derivations
    assert {"target_table", "target_column", "source_table", "source_column", "expression_type"} <= derivations[0].keys()
    assert "targetTable" not in derivations[0]


def test_schema_linker_treats_missing_trust_score_as_low_confidence():
    """新采集字段尚未计算置信度时，Schema Linking 仍应生成摘要。"""
    summary = _build_schema_summary([{"table_name": "demo_order", "columns": [{"name": "id", "trust_score": None}]}])

    assert "id[L]" in summary
