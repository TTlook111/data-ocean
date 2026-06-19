"""SQL Generator Agent 工具单元测试

覆盖 6 个只读工具的过滤逻辑和边界情况。
"""

from __future__ import annotations

import json

import pytest

from dataocean.agent.sql_generation_agent.tools import create_tools


# ── 测试用 fixture ─────────────────────────────────────────────


@pytest.fixture
def sample_state() -> dict:
    """构造包含多种 chunk 类型的 AgentState"""
    return {
        "schema_context": [
            {
                "table_name": "orders",
                "chunk_type": "CORE_TABLE",
                "chunk_text": "订单主表，包含 order_id, user_id, amount 等字段",
                "related_column": "order_id",
                "confidence_score": 90,
                "governance_status": "PUBLISHED",
                "score": 0.95,
            },
            {
                "table_name": "orders",
                "chunk_type": "FIELD_NOTE",
                "chunk_text": "amount 字段单位为分，需除以 100 转换为元",
                "related_column": "amount",
                "confidence_score": 85,
                "governance_status": "PUBLISHED",
                "score": 0.88,
            },
            {
                "table_name": "users",
                "chunk_type": "CORE_TABLE",
                "chunk_text": "用户表，包含 user_id, name, email 等字段",
                "related_column": "user_id",
                "confidence_score": 95,
                "governance_status": "PUBLISHED",
                "score": 0.98,
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
                "chunk_text": "总金额: SUM(amount)/100，按月统计",
                "related_column": "amount",
                "confidence_score": 88,
                "governance_status": "PUBLISHED",
                "score": 0.90,
            },
            {
                "table_name": "products",
                "chunk_type": "CORE_TABLE",
                "chunk_text": "商品表",
                "related_column": "product_id",
                "confidence_score": 80,
                "governance_status": "PUBLISHED",
                "score": 0.85,
            },
        ],
        "retry_count": 0,
        "generated_sql": "",
        "error_message": "",
    }


@pytest.fixture
def tools(sample_state) -> list:
    """创建工具列表"""
    return create_tools(sample_state)


def _find_tool(tools, name: str):
    """从工具列表中按名称查找工具"""
    for t in tools:
        if t.name == name:
            return t
    raise ValueError(f"Tool not found: {name}")


# ── get_schema_context 测试 ───────────────────────────────────


class TestGetSchemaContext:
    """get_schema_context 工具测试"""

    def test_returns_all_chunks(self, tools):
        """无过滤参数时返回全部 chunk"""
        tool = _find_tool(tools, "get_schema_context")
        result = json.loads(tool.invoke({}))
        assert len(result) == 6

    def test_filter_by_table_names(self, tools):
        """按表名过滤"""
        tool = _find_tool(tools, "get_schema_context")
        result = json.loads(tool.invoke({"table_names": ["users"]}))
        assert len(result) == 1
        assert result[0]["table_name"] == "users"

    def test_filter_by_chunk_types(self, tools):
        """按 chunk 类型过滤"""
        tool = _find_tool(tools, "get_schema_context")
        result = json.loads(tool.invoke({"chunk_types": ["JOIN_PATH"]}))
        assert len(result) == 1
        assert result[0]["chunk_type"] == "JOIN_PATH"

    def test_filter_by_table_and_type(self, tools):
        """同时按表名和类型过滤"""
        tool = _find_tool(tools, "get_schema_context")
        result = json.loads(
            tool.invoke({"table_names": ["orders"], "chunk_types": ["CORE_TABLE"]})
        )
        assert len(result) == 1
        assert result[0]["table_name"] == "orders"
        assert result[0]["chunk_type"] == "CORE_TABLE"

    def test_limit(self, tools):
        """限制返回条数"""
        tool = _find_tool(tools, "get_schema_context")
        result = json.loads(tool.invoke({"limit": 2}))
        assert len(result) == 2

    def test_case_insensitive(self, tools):
        """表名过滤不区分大小写"""
        tool = _find_tool(tools, "get_schema_context")
        result = json.loads(tool.invoke({"table_names": ["ORDERS"]}))
        assert len(result) == 4  # orders 有 4 条

    def test_no_match(self, tools):
        """无匹配时返回空列表"""
        tool = _find_tool(tools, "get_schema_context")
        result = json.loads(tool.invoke({"table_names": ["nonexistent"]}))
        assert result == []


# ── get_join_paths 测试 ────────────────────────────────────────


class TestGetJoinPaths:
    """get_join_paths 工具测试"""

    def test_returns_only_join_path(self, tools):
        """只返回 JOIN_PATH 类型"""
        tool = _find_tool(tools, "get_join_paths")
        result = json.loads(tool.invoke({}))
        assert len(result) == 1
        assert result[0]["chunk_type"] == "JOIN_PATH"

    def test_filter_by_table(self, tools):
        """按表名过滤 Join Path"""
        tool = _find_tool(tools, "get_join_paths")
        result = json.loads(tool.invoke({"table_names": ["users"]}))
        assert len(result) == 0  # users 没有 JOIN_PATH

    def test_no_join_paths(self):
        """无 Join Path 时返回空列表"""
        state = {"schema_context": [
            {"table_name": "t1", "chunk_type": "CORE_TABLE", "chunk_text": "test"}
        ]}
        tools = create_tools(state)
        tool = _find_tool(tools, "get_join_paths")
        result = json.loads(tool.invoke({}))
        assert result == []


# ── get_metric_definitions 测试 ────────────────────────────────


class TestGetMetricDefinitions:
    """get_metric_definitions 工具测试"""

    def test_returns_only_metrics(self, tools):
        """只返回 METRIC 类型"""
        tool = _find_tool(tools, "get_metric_definitions")
        result = json.loads(tool.invoke({}))
        assert len(result) == 1
        assert result[0]["chunk_type"] == "METRIC"

    def test_filter_by_keywords(self, tools):
        """按关键词过滤指标"""
        tool = _find_tool(tools, "get_metric_definitions")
        result = json.loads(tool.invoke({"metric_keywords": ["总金额"]}))
        assert len(result) == 1

    def test_no_keyword_match(self, tools):
        """关键词无匹配时返回空"""
        tool = _find_tool(tools, "get_metric_definitions")
        result = json.loads(tool.invoke({"metric_keywords": ["不存在的指标"]}))
        assert result == []


# ── get_field_notes 测试 ───────────────────────────────────────


class TestGetFieldNotes:
    """get_field_notes 工具测试"""

    def test_returns_field_note_and_core_table(self, tools):
        """返回 FIELD_NOTE 和 CORE_TABLE 类型"""
        tool = _find_tool(tools, "get_field_notes")
        result = json.loads(tool.invoke({}))
        chunk_types = {r["chunk_type"] for r in result}
        assert chunk_types <= {"FIELD_NOTE", "CORE_TABLE"}

    def test_filter_by_column(self, tools):
        """按列名过滤"""
        tool = _find_tool(tools, "get_field_notes")
        result = json.loads(tool.invoke({"column_names": ["amount"]}))
        assert len(result) == 1
        assert result[0]["related_column"] == "amount"

    def test_filter_by_table(self, tools):
        """按表名过滤"""
        tool = _find_tool(tools, "get_field_notes")
        result = json.loads(tool.invoke({"table_names": ["products"]}))
        assert len(result) == 1
        assert result[0]["table_name"] == "products"


# ── get_generation_feedback 测试 ──────────────────────────────


class TestGetGenerationFeedback:
    """get_generation_feedback 工具测试"""

    def test_returns_feedback(self, tools):
        """正常返回 feedback"""
        tool = _find_tool(tools, "get_generation_feedback")
        result = json.loads(tool.invoke({}))
        assert "retry_count" in result
        assert "previous_sql" in result
        assert "error_message" in result

    def test_include_previous_sql_false(self, tools):
        """include_previous_sql=False 时 previous_sql 为空"""
        tool = _find_tool(tools, "get_generation_feedback")
        result = json.loads(tool.invoke({"include_previous_sql": False}))
        assert result["previous_sql"] == ""

    def test_with_retry_context(self):
        """重试场景下返回上一轮 SQL 和错误"""
        state = {
            "schema_context": [],
            "retry_count": 1,
            "generated_sql": "SELECT * FROM orders",
            "error_message": "语法错误",
        }
        tools = create_tools(state)
        tool = _find_tool(tools, "get_generation_feedback")
        result = json.loads(tool.invoke({}))
        assert result["retry_count"] == 1
        assert result["previous_sql"] == "SELECT * FROM orders"
        assert result["error_message"] == "语法错误"


# ── lint_sql_draft 测试 ───────────────────────────────────────


class TestLintSqlDraft:
    """lint_sql_draft 工具测试"""

    def test_valid_select(self, tools):
        """合法 SELECT 语句通过检查"""
        tool = _find_tool(tools, "lint_sql_draft")
        result = json.loads(tool.invoke({"sql": "SELECT id FROM users WHERE id = 1"}))
        assert result["valid"] is True
        assert result["issues"] == []

    def test_non_select(self, tools):
        """非 SELECT 语句被标记"""
        tool = _find_tool(tools, "lint_sql_draft")
        result = json.loads(tool.invoke({"sql": "DELETE FROM users WHERE id = 1"}))
        assert result["valid"] is False
        assert any("不是 SELECT" in i for i in result["issues"])

    def test_dangerous_keyword(self, tools):
        """包含危险关键词被标记"""
        tool = _find_tool(tools, "lint_sql_draft")
        result = json.loads(
            tool.invoke({"sql": "SELECT * FROM users; DROP TABLE users"})
        )
        assert result["valid"] is False
        assert any("危险关键词" in i for i in result["issues"])

    def test_column_name_not_false_positive(self, tools):
        """列名中的 UPDATE/INSERT 子串不误报"""
        tool = _find_tool(tools, "lint_sql_draft")
        result = json.loads(
            tool.invoke({"sql": "SELECT update_time, insert_by FROM orders"})
        )
        assert result["valid"] is True
        assert result["issues"] == []

    def test_multiple_statements(self, tools):
        """多语句分号被标记"""
        tool = _find_tool(tools, "lint_sql_draft")
        result = json.loads(
            tool.invoke({"sql": "SELECT * FROM t1; SELECT * FROM t2"})
        )
        assert result["valid"] is False
        assert any("多语句" in i for i in result["issues"])

    def test_select_without_from(self, tools):
        """缺少 FROM 子句被标记"""
        tool = _find_tool(tools, "lint_sql_draft")
        result = json.loads(tool.invoke({"sql": "SELECT 1 + 1"}))
        assert result["valid"] is False
        assert any("FROM" in i for i in result["issues"])

    def test_select_from_dual(self, tools):
        """SELECT FROM DUAL 不被标记缺 FROM"""
        tool = _find_tool(tools, "lint_sql_draft")
        result = json.loads(tool.invoke({"sql": "SELECT 1 FROM DUAL"}))
        assert result["valid"] is True
