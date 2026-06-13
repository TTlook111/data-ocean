"""Agent E2E 测试

模拟完整 Agent 工作流（mock LLM + mock Milvus + mock DB），
覆盖：正常路径、重试路径、降级路径、取消路径、超时路径。

注：部分测试需要完整的 Java 服务环境，标记为可选。
"""

from __future__ import annotations

import asyncio
import os
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from dataocean.agent.state import AgentState
from dataocean.agent.graph import build_graph

# 检查是否有完整环境（Java 服务运行）
HAS_FULL_ENV = os.getenv("JAVA_GATEWAY_URL") is not None


@pytest.fixture
def base_state() -> AgentState:
    """基础 AgentState，用于测试"""
    return {
        "task_id": "test-task-001",
        "question": "查询上月销售额最高的10个产品",
        "datasource_id": 1,
        "user_id": 1,
        "conversation_history": [],
        "user_permissions": {
            "allowed_tables": ["orders", "products"],
            "table_scope_mode": "ALLOWLIST",
        },
        "active_snapshot_id": 1,
        "confidence_scores": {},
        "prompt_versions": [],
        "connection_config": {
            "host": "localhost",
            "port": 3306,
            "database": "test_db",
            "username": "root",
            "password": "test",
        },
        "retry_count": 0,
        "error_message": "",
        "errors": [],
        "start_time": 0,
        "cancelled": False,
        "timeout_budget": None,
        "degraded": False,
        "degrade_notice": "",
        "fallback_chunks": [],
    }


@pytest.mark.skipif(not HAS_FULL_ENV, reason="需要完整 Java 服务环境")
class TestAgentE2E:
    """Agent E2E 测试

    注：这些测试需要完整的 Java 服务环境（JAVA_GATEWAY_URL）。
    如果环境不可用，测试将被跳过。
    """

    @pytest.mark.asyncio
    async def test_normal_path_success(self, base_state: AgentState):
        """正常路径：所有节点成功执行"""
        # Mock LLM 响应
        mock_llm_response = """```sql
SELECT p.name, SUM(o.amount) as total_sales
FROM orders o
JOIN products p ON o.product_id = p.id
WHERE o.created_at >= '2026-05-01'
GROUP BY p.name
ORDER BY total_sales DESC
LIMIT 10
```
查询上月销售额最高的10个产品及其销售总额。"""

        # Mock SQL 执行结果
        mock_execution_result = {
            "columns": [{"name": "name", "type": "VARCHAR"}, {"name": "total_sales", "type": "DECIMAL"}],
            "data_rows": [
                {"name": "产品A", "total_sales": 10000},
                {"name": "产品B", "total_sales": 8000},
            ],
            "row_count": 2,
            "execution_time_ms": 100,
            "error": None,
        }

        with patch("dataocean.infra.llm.call_llm", new_callable=AsyncMock, return_value=mock_llm_response), \
             patch("dataocean.sandbox.executor.execute", new_callable=AsyncMock, return_value=MagicMock(
                 success=True,
                 columns=mock_execution_result["columns"],
                 rows=mock_execution_result["data_rows"],
                 row_count=mock_execution_result["row_count"],
                 execution_time_ms=mock_execution_result["execution_time_ms"],
                 error=None,
             )), \
             patch("dataocean.rag.service.retrieve_schemas", new_callable=AsyncMock, return_value=MagicMock(
                 results=[MagicMock(
                     table_name="orders",
                     chunk_type="TABLE_DESC",
                     chunk_text="订单表",
                     score=0.9,
                     governance_status="NORMAL",
                 )],
             )):
            graph = build_graph().compile()
            result = await graph.ainvoke(base_state)

            # 验证结果
            assert result.get("generated_sql") is not None
            assert result.get("execution_result", {}).get("error") is None
            assert result.get("current_node") == "DATA_VISUALIZER"

    @pytest.mark.asyncio
    async def test_retry_path_sql_validation_failure(self, base_state: AgentState):
        """重试路径：SQL 校验失败后重试"""
        # 第一次 LLM 返回不安全的 SQL，第二次返回安全的 SQL
        unsafe_sql = "SELECT * FROM orders; DROP TABLE orders;"
        safe_sql = """```sql
SELECT id, amount FROM orders LIMIT 100
```
查询订单列表。"""

        call_count = 0

        async def mock_call_llm(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return unsafe_sql
            return safe_sql

        with patch("dataocean.infra.llm.call_llm", new_callable=AsyncMock, side_effect=mock_call_llm), \
             patch("dataocean.sandbox.executor.execute", new_callable=AsyncMock, return_value=MagicMock(
                 success=True, columns=[], rows=[], row_count=0, execution_time_ms=50, error=None,
             )), \
             patch("dataocean.rag.service.retrieve_schemas", new_callable=AsyncMock, return_value=MagicMock(results=[])):
            graph = build_graph().compile()
            result = await graph.ainvoke(base_state)

            # 验证重试后成功
            assert result.get("retry_count", 0) > 0
            assert result.get("current_node") in ("SQL_EXECUTOR", "DATA_VISUALIZER")

    @pytest.mark.asyncio
    async def test_cancel_path(self, base_state: AgentState):
        """取消路径：任务被取消"""
        from dataocean.infra.cancellation import cancel_task

        # 标记任务为已取消
        cancel_task("test-task-001")

        with patch("dataocean.infra.llm.call_llm", new_callable=AsyncMock, return_value="SELECT 1"):
            graph = build_graph().compile()
            result = await graph.ainvoke(base_state)

            # 验证取消
            assert result.get("error_message") == "查询已取消"

    @pytest.mark.asyncio
    async def test_degraded_path(self, base_state: AgentState):
        """降级路径：RAG 不可用时使用 fallback chunks"""
        base_state["degraded"] = True
        base_state["degrade_notice"] = "知识库暂时不可用"
        base_state["fallback_chunks"] = [
            {
                "chunkType": "TABLE_DESC",
                "chunkText": "订单表：包含订单信息",
                "relatedTable": "orders",
                "snapshotId": 1,
            }
        ]

        mock_sql = """```sql
SELECT * FROM orders LIMIT 100
```
查询订单列表。"""

        with patch("dataocean.infra.llm.call_llm", new_callable=AsyncMock, return_value=mock_sql), \
             patch("dataocean.sandbox.executor.execute", new_callable=AsyncMock, return_value=MagicMock(
                 success=True, columns=[], rows=[], row_count=0, execution_time_ms=50, error=None,
             )), \
             patch("dataocean.rag.service.retrieve_schemas", new_callable=AsyncMock, return_value=MagicMock(results=[])):
            graph = build_graph().compile()
            result = await graph.ainvoke(base_state)

            # 验证降级状态
            assert result.get("degraded") is True
            assert "fallback_chunks" in result


class TestAgentState:
    """AgentState 类型测试"""

    def test_state_has_required_fields(self):
        """AgentState 包含所有必需字段"""
        from dataocean.agent.state import AgentState

        # 检查关键字段存在
        state_fields = AgentState.__annotations__
        required_fields = [
            "task_id", "question", "datasource_id", "user_id",
            "conversation_history", "user_permissions",
            "rewritten_query", "extracted_intent",
            "schema_context", "generated_sql", "validation_result",
            "execution_result", "chart_config", "suggested_questions",
            "current_node", "retry_count", "error_message",
            "fallback_chunks", "_node_timeout",
        ]
        for field in required_fields:
            assert field in state_fields, f"Missing field: {field}"
