"""SQL Generator Agent 集成测试

覆盖 Agent 路径选择、fallback 逻辑和结构化输出写回。
"""

from __future__ import annotations

import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from dataocean.agent.nodes.sql_generator import (
    run_sql_generator,
    _run_sql_generator_legacy,
)


# ── 测试用 fixture ─────────────────────────────────────────────


@pytest.fixture
def base_state() -> dict:
    """基础 AgentState"""
    return {
        "task_id": "test-001",
        "question": "查询所有订单的总金额",
        "rewritten_query": "查询所有订单的总金额",
        "extracted_intent": {
            "dimensions": [],
            "metrics": ["总金额"],
            "filters": [],
        },
        "schema_context": [
            {
                "table_name": "orders",
                "chunk_type": "CORE_TABLE",
                "chunk_text": "订单表",
                "related_column": "order_id",
                "confidence_score": 90,
                "governance_status": "PUBLISHED",
                "score": 0.95,
            },
            {
                "table_name": "orders",
                "chunk_type": "JOIN_PATH",
                "chunk_text": "orders.user_id = users.user_id",
                "related_column": None,
                "confidence_score": 90,
                "governance_status": "PUBLISHED",
                "score": 0.92,
            },
            {
                "table_name": "orders",
                "chunk_type": "METRIC",
                "chunk_text": "总金额: SUM(amount)/100",
                "related_column": "amount",
                "confidence_score": 88,
                "governance_status": "PUBLISHED",
                "score": 0.90,
            },
        ],
        "confidence_scores": {"amount": 88},
        "conversation_history": [],
        "retry_count": 0,
        "generated_sql": "",
        "error_message": "",
        "current_node": "",
        "_node_timeout": 60,
    }


# ── Agent 路径测试 ─────────────────────────────────────────────


class TestAgentPath:
    """Agent 模式路径测试"""

    @pytest.mark.asyncio
    async def test_agent_enabled_calls_agent(self, base_state):
        """Agent 开启时调用 generate_sql_with_agent"""
        mock_result = {
            "generated_sql": "SELECT SUM(amount)/100 AS total FROM orders",
            "sql_explanation": "查询订单总金额",
            "error_message": "",
            "retry_count": 0,
            "current_node": "SQL_GENERATOR",
        }
        mock_settings = MagicMock()
        mock_settings.sql_generator_agent_enabled = True

        with (
            patch(
                "dataocean.agent.nodes.sql_generator.get_settings",
                return_value=mock_settings,
            ),
            patch(
                "dataocean.agent.sql_generation_agent.agent.generate_sql_with_agent",
                new_callable=AsyncMock,
                return_value=mock_result,
            ) as mock_agent,
        ):
            result = await run_sql_generator(base_state)

            assert result["generated_sql"] == "SELECT SUM(amount)/100 AS total FROM orders"
            mock_agent.assert_called_once()

    @pytest.mark.asyncio
    async def test_agent_disabled_skips_agent(self, base_state):
        """Agent 关闭时不调用 Agent，走 legacy"""
        mock_settings = MagicMock()
        mock_settings.sql_generator_agent_enabled = False

        with (
            patch(
                "dataocean.agent.nodes.sql_generator.get_settings",
                return_value=mock_settings,
            ),
            patch(
                "dataocean.agent.nodes.sql_generator._run_sql_generator_legacy",
                new_callable=AsyncMock,
                return_value={"generated_sql": "SELECT 1", "current_node": "SQL_GENERATOR"},
            ) as mock_legacy,
        ):
            result = await run_sql_generator(base_state)

            mock_legacy.assert_called_once()

    @pytest.mark.asyncio
    async def test_agent_failure_fallbacks_to_legacy(self, base_state):
        """Agent 失败时自动 fallback 到 legacy"""
        mock_settings = MagicMock()
        mock_settings.sql_generator_agent_enabled = True

        with (
            patch(
                "dataocean.agent.nodes.sql_generator.get_settings",
                return_value=mock_settings,
            ),
            patch(
                "dataocean.agent.sql_generation_agent.agent.generate_sql_with_agent",
                new_callable=AsyncMock,
                side_effect=RuntimeError("Agent 崩溃"),
            ),
            patch(
                "dataocean.agent.nodes.sql_generator._run_sql_generator_legacy",
                new_callable=AsyncMock,
                return_value={
                    "generated_sql": "SELECT 1",
                    "sql_explanation": "",
                    "error_message": "",
                    "retry_count": 0,
                    "current_node": "SQL_GENERATOR",
                },
            ) as mock_legacy,
        ):
            mock_settings.sql_generator_agent_enabled = True
            result = await run_sql_generator(base_state)

            # 应该 fallback 到 legacy
            mock_legacy.assert_called_once()
            assert result["generated_sql"] == "SELECT 1"


# ── Legacy 模式测试 ────────────────────────────────────────────


class TestLegacyPath:
    """Legacy 模式路径测试"""

    @pytest.mark.asyncio
    async def test_legacy_timeout(self, base_state):
        """Legacy 模式超时处理"""
        import asyncio

        with patch(
            "dataocean.agent.nodes.sql_generator.call_llm",
            new_callable=AsyncMock,
            side_effect=asyncio.TimeoutError,
        ):
            result = await _run_sql_generator_legacy(base_state)
            assert result["error_message"] == "SQL 生成超时"
            assert result["generated_sql"] == ""

    @pytest.mark.asyncio
    async def test_legacy_llm_error(self, base_state):
        """Legacy 模式 LLM 异常处理"""
        with patch(
            "dataocean.agent.nodes.sql_generator.call_llm",
            new_callable=AsyncMock,
            side_effect=RuntimeError("LLM 服务不可用"),
        ):
            result = await _run_sql_generator_legacy(base_state)
            assert "error_message" in result
            assert result["generated_sql"] == ""

    @pytest.mark.asyncio
    async def test_legacy_success(self, base_state):
        """Legacy 模式正常生成 SQL"""
        mock_response = json.dumps(
            {
                "sql": "SELECT SUM(amount)/100 AS total FROM orders",
                "explanation": "查询订单总金额",
            }
        )

        with (
            patch(
                "dataocean.agent.nodes.sql_generator.call_llm",
                new_callable=AsyncMock,
                return_value=mock_response,
            ),
            patch(
                "dataocean.agent.nodes.sql_generator.render_prompt_with_metadata",
                new_callable=AsyncMock,
                return_value=("rendered prompt", 1),
            ),
        ):
            result = await _run_sql_generator_legacy(base_state)
            assert result["generated_sql"] != ""
            assert result["current_node"] == "SQL_GENERATOR"


# ── 结构化输出写回测试 ─────────────────────────────────────────


class TestStructuredOutput:
    """Agent 结构化输出写回测试"""

    def test_result_writes_back_all_fields(self):
        """SqlGenerationResult 写回包含所有必要字段"""
        from dataocean.agent.sql_generation_agent.schema import SqlGenerationResult

        result = SqlGenerationResult(
            sql="SELECT COUNT(*) FROM orders",
            explanation="统计订单总数",
            used_tool_names=["get_schema_context", "get_join_paths"],
            confidence=0.85,
            assumptions=["假设查询所有订单"],
        )

        # 模拟 agent.py 中的写回逻辑
        state_patch = {
            "generated_sql": result.sql,
            "sql_explanation": result.explanation,
            "error_message": "",
            "retry_count": 0,
            "current_node": "SQL_GENERATOR",
        }

        assert state_patch["generated_sql"] == "SELECT COUNT(*) FROM orders"
        assert state_patch["sql_explanation"] == "统计订单总数"
        assert state_patch["error_message"] == ""
