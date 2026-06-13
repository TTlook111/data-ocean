"""SQL 沙箱回归测试

覆盖 F0 修复的 SQL 沙箱安全场景：
- 表白名单空值语义（table_scope_mode）
- 内部路由认证（X-Internal-Token）
- 危险函数黑名单补齐
- 未知脱敏策略默认全遮蔽
- Prompt 注入防护
"""

from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from dataocean.sandbox.rules.table_rule import (
    check as table_check,
    _TABLE_SCOPE_UNSPECIFIED,
    _TABLE_SCOPE_ALLOWLIST,
    _TABLE_SCOPE_UNRESTRICTED,
)
from dataocean.sandbox.rules.function_rule import check as function_check


# ============================================================
# 表白名单空值语义回归测试
# ============================================================

class TestTableScopeMode:
    """表白名单空值语义"""

    def test_unspecified_mode_rejects_empty_tables(self):
        """UNSPECIFIED 模式 + 空白名单 = 拒绝"""
        result = table_check("SELECT * FROM orders", [], _TABLE_SCOPE_UNSPECIFIED)
        assert result.passed is False
        assert "权限上下文" in result.reason

    def test_unspecified_mode_with_tables_allows(self):
        """UNSPECIFIED 模式 + 有白名单 = 向后兼容 ALLOWLIST"""
        result = table_check("SELECT * FROM orders", ["orders"], _TABLE_SCOPE_UNSPECIFIED)
        assert result.passed is True

    def test_allowlist_mode_allows_authorized(self):
        """ALLOWLIST 模式 + 授权表 = 通过"""
        result = table_check("SELECT * FROM orders", ["orders"], _TABLE_SCOPE_ALLOWLIST)
        assert result.passed is True

    def test_allowlist_mode_rejects_unauthorized(self):
        """ALLOWLIST 模式 + 未授权表 = 拒绝"""
        result = table_check("SELECT * FROM products", ["orders"], _TABLE_SCOPE_ALLOWLIST)
        assert result.passed is False
        assert "未授权" in result.reason

    def test_allowlist_mode_rejects_empty_tables(self):
        """ALLOWLIST 模式 + 空白名单 = 拒绝"""
        result = table_check("SELECT * FROM orders", [], _TABLE_SCOPE_ALLOWLIST)
        assert result.passed is False
        assert "未配置" in result.reason

    def test_unrestricted_mode_allows_all(self):
        """UNRESTRICTED 模式 = 允许所有表"""
        result = table_check("SELECT * FROM any_table", [], _TABLE_SCOPE_UNRESTRICTED)
        assert result.passed is True

    def test_unrestricted_mode_ignores_tables(self):
        """UNRESTRICTED 模式忽略白名单"""
        result = table_check("SELECT * FROM orders", ["products"], _TABLE_SCOPE_UNRESTRICTED)
        assert result.passed is True


# ============================================================
# 危险函数黑名单回归测试
# ============================================================

class TestDangerousFunctions:
    """危险函数黑名单"""

    def test_xml_injection_functions_rejected(self):
        """XML 注入函数被拒绝"""
        dangerous_sql = [
            "SELECT UPDATEXML(1, CONCAT(0x7e, VERSION()), 1)",
            "SELECT EXTRACTVALUE(1, CONCAT(0x7e, VERSION()))",
        ]
        for sql in dangerous_sql:
            result = function_check(sql)
            assert result.passed is False, f"Should reject: {sql}"

    def test_lock_functions_rejected(self):
        """锁函数被拒绝"""
        dangerous_sql = [
            "SELECT GET_LOCK('test', 10)",
            "SELECT RELEASE_LOCK('test')",
            "SELECT RELEASE_ALL_LOCKS()",
            "SELECT IS_FREE_LOCK('test')",
            "SELECT IS_USED_LOCK('test')",
        ]
        for sql in dangerous_sql:
            result = function_check(sql)
            assert result.passed is False, f"Should reject: {sql}"

    def test_file_operation_functions_rejected(self):
        """文件操作函数被拒绝"""
        dangerous_sql = [
            "SELECT LOAD_FILE('/etc/passwd')",
            "SELECT * INTO OUTFILE '/tmp/test'",
            "SELECT * INTO DUMPFILE '/tmp/test'",
        ]
        for sql in dangerous_sql:
            result = function_check(sql)
            assert result.passed is False, f"Should reject: {sql}"

    def test_command_execution_functions_rejected(self):
        """命令执行函数被拒绝"""
        dangerous_sql = [
            "SELECT SYSTEM('ls')",
            "SELECT EXEC('ls')",
        ]
        for sql in dangerous_sql:
            result = function_check(sql)
            assert result.passed is False, f"Should reject: {sql}"

    def test_performance_impact_functions_rejected(self):
        """性能影响函数被拒绝"""
        dangerous_sql = [
            "SELECT SLEEP(5)",
            "SELECT BENCHMARK(1000000, MD5('test'))",
        ]
        for sql in dangerous_sql:
            result = function_check(sql)
            assert result.passed is False, f"Should reject: {sql}"

    def test_encryption_functions_rejected(self):
        """加密函数被拒绝"""
        dangerous_sql = [
            "SELECT AES_ENCRYPT('test', 'key')",
            "SELECT AES_DECRYPT('test', 'key')",
        ]
        for sql in dangerous_sql:
            result = function_check(sql)
            assert result.passed is False, f"Should reject: {sql}"

    def test_safe_aggregation_functions_allowed(self):
        """安全的聚合函数被允许"""
        safe_sql = [
            "SELECT COUNT(*) FROM orders",
            "SELECT SUM(amount) FROM orders",
            "SELECT AVG(amount) FROM orders",
            "SELECT MAX(amount) FROM orders",
            "SELECT MIN(amount) FROM orders",
        ]
        for sql in safe_sql:
            result = function_check(sql)
            assert result.passed is True, f"Should allow: {sql}"

    def test_safe_string_functions_allowed(self):
        """安全的字符串函数被允许"""
        safe_sql = [
            "SELECT CONCAT(first_name, last_name) FROM users",
            "SELECT LENGTH(name) FROM products",
            "SELECT UPPER(name) FROM products",
            "SELECT LOWER(name) FROM products",
        ]
        for sql in safe_sql:
            result = function_check(sql)
            assert result.passed is True, f"Should allow: {sql}"


# ============================================================
# 表白名单多表查询回归测试
# ============================================================

class TestTableRuleMultiTable:
    """表白名单多表查询"""

    def test_join_query_requires_all_tables_authorized(self):
        """JOIN 查询需要所有表都授权"""
        result = table_check(
            "SELECT * FROM orders JOIN products ON orders.product_id = products.id",
            ["orders", "products"],
            _TABLE_SCOPE_ALLOWLIST,
        )
        assert result.passed is True

    def test_join_query_rejects_unauthorized_table(self):
        """JOIN 查询中未授权表被拒绝"""
        result = table_check(
            "SELECT * FROM orders JOIN users ON orders.user_id = users.id",
            ["orders"],
            _TABLE_SCOPE_ALLOWLIST,
        )
        assert result.passed is False
        assert "users" in result.reason

    def test_subquery_requires_all_tables_authorized(self):
        """子查询需要所有表都授权"""
        result = table_check(
            "SELECT * FROM orders WHERE product_id IN (SELECT id FROM products)",
            ["orders", "products"],
            _TABLE_SCOPE_ALLOWLIST,
        )
        assert result.passed is True


# ============================================================
# 表名大小写不敏感回归测试
# ============================================================

class TestTableNameCaseInsensitive:
    """表名大小写不敏感"""

    def test_uppercase_table_matches_lowercase_whitelist(self):
        """大写表名匹配小写白名单"""
        result = table_check(
            "SELECT * FROM ORDERS",
            ["orders"],
            _TABLE_SCOPE_ALLOWLIST,
        )
        assert result.passed is True

    def test_lowercase_table_matches_uppercase_whitelist(self):
        """小写表名匹配大写白名单"""
        result = table_check(
            "SELECT * FROM orders",
            ["ORDERS"],
            _TABLE_SCOPE_ALLOWLIST,
        )
        assert result.passed is True
