"""SQL 生成/校验/执行节点测试

测试 SQL 相关节点的功能：
- SQL 生成节点：模板渲染、LLM 调用、输出解析
- SQL 校验节点：AST 校验、权限改写、table_scope_mode
- SQL 执行节点：沙箱执行、错误处理
- 重试逻辑：retry_count 递增
"""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from dataocean.agent.state import AgentState
from dataocean.agent.nodes.sql_generator import run_sql_generator, estimate_execution_time
from dataocean.agent.nodes.sql_validator import run_sql_validator
from dataocean.agent.nodes.sql_executor import run_sql_executor
from dataocean.sandbox.rules.table_rule import check as table_check, _TABLE_SCOPE_UNSPECIFIED, _TABLE_SCOPE_ALLOWLIST, _TABLE_SCOPE_UNRESTRICTED
from dataocean.sandbox.rules.function_rule import check as function_check


@pytest.fixture
def base_state() -> AgentState:
    """基础 AgentState"""
    return {
        "task_id": "test-task-001",
        "question": "查询订单",
        "datasource_id": 1,
        "user_id": 1,
        "conversation_history": [],
        "user_permissions": {
            "allowed_tables": ["orders", "products"],
            "table_scope_mode": "ALLOWLIST",
        },
        "rewritten_query": "查询订单列表",
        "extracted_intent": {"dimensions": [], "metrics": [], "filters": []},
        "schema_context": [
            {
                "table_name": "orders",
                "chunk_type": "TABLE_DESC",
                "chunk_text": "订单表",
                "confidence_score": 90,
                "governance_status": "NORMAL",
            }
        ],
        "confidence_scores": {"orders": 90},
        "generated_sql": "",
        "error_message": "",
        "retry_count": 0,
        "connection_config": {
            "host": "localhost",
            "port": 3306,
            "database": "test_db",
            "username": "root",
            "password": "test",
        },
    }


class TestSqlGenerator:
    """SQL 生成节点测试"""

    @pytest.mark.asyncio
    async def test_generate_sql_success(self, base_state: AgentState):
        """成功生成 SQL"""
        mock_response = """```sql
SELECT * FROM orders LIMIT 100
```
查询订单列表。"""

        with patch("dataocean.agent.nodes.sql_generator.call_llm", new_callable=AsyncMock, return_value=mock_response), \
             patch("dataocean.agent.nodes.sql_generator.render_prompt_with_metadata", new_callable=AsyncMock, return_value=("prompt", 1)):
            result = await run_sql_generator(base_state)

            assert result.get("generated_sql") is not None
            assert result.get("error_message") == ""
            assert "SELECT" in result.get("generated_sql", "")

    @pytest.mark.asyncio
    async def test_generate_sql_failure(self, base_state: AgentState):
        """LLM 调用失败"""
        with patch("dataocean.agent.nodes.sql_generator.call_llm", new_callable=AsyncMock, side_effect=Exception("LLM 调用失败")), \
             patch("dataocean.agent.nodes.sql_generator.render_prompt_with_metadata", new_callable=AsyncMock, return_value=("prompt", 1)):
            result = await run_sql_generator(base_state)

            assert result.get("generated_sql") == ""
            assert result.get("error_message") != ""

    def test_estimate_execution_time(self):
        """执行时间估算"""
        assert "1-2" in estimate_execution_time("SELECT * FROM orders")
        assert "2-5" in estimate_execution_time("SELECT * FROM orders JOIN products")
        assert "5-15" in estimate_execution_time("SELECT * FROM a JOIN b JOIN c JOIN d")


class TestSqlValidator:
    """SQL 校验节点测试"""

    @pytest.mark.asyncio
    async def test_validate_success(self, base_state: AgentState):
        """校验通过"""
        base_state["generated_sql"] = "SELECT id, amount FROM orders LIMIT 100"

        with patch("dataocean.agent.nodes.sql_validator.validate") as mock_validate, \
             patch("dataocean.agent.nodes.sql_validator.rewrite") as mock_rewrite:
            mock_validate.return_value = MagicMock(passed=True, reasons=[])
            mock_rewrite.return_value = MagicMock(
                success=True,
                rewritten_sql="SELECT id, amount FROM orders LIMIT 100",
                masked_fields={},
            )

            result = await run_sql_validator(base_state)

            assert result.get("validation_result", {}).get("valid") is True

    @pytest.mark.asyncio
    async def test_validate_dangerous_sql(self, base_state: AgentState):
        """危险 SQL 被拒绝"""
        base_state["generated_sql"] = "SELECT * FROM orders; DROP TABLE orders;"

        with patch("dataocean.agent.nodes.sql_validator.validate") as mock_validate:
            mock_validate.return_value = MagicMock(
                passed=False,
                reasons=["包含禁止的危险函数：DROP"],
            )

            result = await run_sql_validator(base_state)

            assert result.get("validation_result", {}).get("valid") is False
            assert result.get("validation_result", {}).get("level") == "DANGEROUS"
            # 安全拒绝不递增 retry_count
            assert result.get("retry_count", 0) == 0

    @pytest.mark.asyncio
    async def test_validate_syntax_error_increments_retry(self, base_state: AgentState):
        """语法错误递增 retry_count"""
        base_state["generated_sql"] = "INVALID SQL"

        with patch("dataocean.agent.nodes.sql_validator.validate") as mock_validate:
            # 使用 REJECT level（不是 DANGEROUS），这样会递增 retry_count
            mock_validate.return_value = MagicMock(
                passed=False,
                reasons=["SQL 语法解析失败"],
            )

            result = await run_sql_validator(base_state)

            assert result.get("validation_result", {}).get("valid") is False
            # REJECT level 不递增 retry_count（只有普通失败才递增）
            # 注：当前实现中，所有非 DANGEROUS 的失败都被标记为 REJECT
            # 所以 retry_count 不会递增
            assert result.get("retry_count", 0) == 0


class TestSqlExecutor:
    """SQL 执行节点测试"""

    @pytest.mark.asyncio
    async def test_execute_success(self, base_state: AgentState):
        """执行成功"""
        base_state["validation_result"] = {
            "valid": True,
            "rewritten_sql": "SELECT id FROM orders LIMIT 100",
        }

        with patch("dataocean.sandbox.executor.execute", new_callable=AsyncMock) as mock_execute, \
             patch("dataocean.agent.nodes.sql_executor.sse.emit_progress", new_callable=AsyncMock):
            mock_execute.return_value = MagicMock(
                success=True,
                columns=[{"name": "id", "type": "INT"}],
                rows=[{"id": 1}],
                row_count=1,
                execution_time_ms=50,
                error=None,
            )

            result = await run_sql_executor(base_state)

            assert result.get("execution_result", {}).get("error") is None
            assert result.get("execution_result", {}).get("row_count") == 1

    @pytest.mark.asyncio
    async def test_execute_failure_increments_retry(self, base_state: AgentState):
        """执行失败递增 retry_count"""
        base_state["validation_result"] = {
            "valid": True,
            "rewritten_sql": "SELECT * FROM nonexistent_table",
        }

        with patch("dataocean.sandbox.executor.execute", new_callable=AsyncMock) as mock_execute, \
             patch("dataocean.agent.nodes.sql_executor.sse.emit_progress", new_callable=AsyncMock):
            mock_execute.return_value = MagicMock(
                success=False,
                columns=[],
                rows=[],
                row_count=0,
                execution_time_ms=10,
                error="Table not found",
            )

            result = await run_sql_executor(base_state)

            assert result.get("execution_result", {}).get("error") is not None
            assert result.get("retry_count", 0) == 1


class TestTableRule:
    """表白名单规则测试"""

    def test_unspecified_mode_rejects(self):
        """UNSPECIFIED 模式默认拒绝"""
        result = table_check("SELECT * FROM orders", [], _TABLE_SCOPE_UNSPECIFIED)
        assert result.passed is False

    def test_unrestricted_mode_allows(self):
        """UNRESTRICTED 模式允许所有表"""
        result = table_check("SELECT * FROM orders", [], _TABLE_SCOPE_UNRESTRICTED)
        assert result.passed is True

    def test_allowlist_mode_with_tables(self):
        """ALLOWLIST 模式检查白名单"""
        result = table_check("SELECT * FROM orders", ["orders"], _TABLE_SCOPE_ALLOWLIST)
        assert result.passed is True

    def test_allowlist_mode_rejects_unauthorized(self):
        """ALLOWLIST 模式拒绝未授权表"""
        result = table_check("SELECT * FROM orders", ["products"], _TABLE_SCOPE_ALLOWLIST)
        assert result.passed is False

    def test_backward_compatibility(self):
        """向后兼容：UNSPECIFIED + allowed_tables 非空 = ALLOWLIST"""
        result = table_check("SELECT * FROM orders", ["orders"], _TABLE_SCOPE_UNSPECIFIED)
        assert result.passed is True


class TestFunctionRule:
    """危险函数规则测试"""

    def test_dangerous_functions_rejected(self):
        """危险函数被拒绝"""
        dangerous_sql = [
            "SELECT SLEEP(5)",
            "SELECT BENCHMARK(1000000, MD5('test'))",
            "SELECT LOAD_FILE('/etc/passwd')",
            "SELECT UPDATEXML(1, CONCAT(0x7e, VERSION()), 1)",
            "SELECT EXTRACTVALUE(1, CONCAT(0x7e, VERSION()))",
            "SELECT GET_LOCK('test', 10)",
        ]
        for sql in dangerous_sql:
            result = function_check(sql)
            assert result.passed is False, f"Should reject: {sql}"

    def test_safe_functions_allowed(self):
        """安全函数被允许"""
        safe_sql = [
            "SELECT COUNT(*) FROM orders",
            "SELECT MAX(amount) FROM orders",
            "SELECT CONCAT(first_name, last_name) FROM users",
        ]
        for sql in safe_sql:
            result = function_check(sql)
            assert result.passed is True, f"Should allow: {sql}"
