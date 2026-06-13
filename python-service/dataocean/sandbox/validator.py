"""SQL AST 校验引擎

按顺序执行规则链，聚合所有校验结果。
规则链顺序：statement → function → depth → star → table → limit
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field

from .rules import RuleResult
from .rules import statement_rule, function_rule, depth_rule, star_rule, table_rule, limit_rule

logger = logging.getLogger(__name__)


@dataclass
class ValidationResult:
    """校验引擎聚合结果"""

    passed: bool
    issues: list[RuleResult] = field(default_factory=list)

    @property
    def reasons(self) -> list[str]:
        """返回所有失败原因"""
        return [issue.reason for issue in self.issues if not issue.passed]


def validate(
    sql: str,
    allowed_tables: list[str] | None = None,
    table_scope_mode: str = "UNSPECIFIED",
) -> ValidationResult:
    """执行完整的 SQL 安全校验规则链

    Args:
        sql: 待校验的 SQL 语句
        allowed_tables: 授权表白名单
        table_scope_mode: 表访问范围模式
            - UNSPECIFIED: 未提供权限上下文，默认拒绝
            - ALLOWLIST: 使用白名单模式
            - UNRESTRICTED: 显式全库开放

    Returns:
        ValidationResult 包含是否通过和所有失败项
    """
    if not sql or not sql.strip():
        return ValidationResult(
            passed=False,
            issues=[RuleResult(passed=False, rule_name="empty", reason="SQL 为空")],
        )

    # SQL 注入二次防护：检测注释符号和多语句分隔符
    injection_check = _check_injection_patterns(sql)
    if injection_check:
        return ValidationResult(passed=False, issues=[injection_check])

    results: list[RuleResult] = []

    # 按顺序执行规则链
    results.append(statement_rule.check(sql))
    results.append(function_rule.check(sql))
    results.append(depth_rule.check(sql))
    results.append(star_rule.check(sql))
    results.append(table_rule.check(sql, allowed_tables, table_scope_mode))
    results.append(limit_rule.check(sql))

    # 聚合结果
    failed = [r for r in results if not r.passed]
    passed = len(failed) == 0

    if not passed:
        logger.warning("SQL 校验不通过 reasons=%s sql=%s", [r.reason for r in failed], sql[:100])

    return ValidationResult(passed=passed, issues=failed)


def _check_injection_patterns(sql: str) -> RuleResult | None:
    """检测 SQL 注入常见模式：注释符号和多语句分隔符"""
    # 检测行注释 --
    if re.search(r"--\s", sql):
        return RuleResult(passed=False, rule_name="injection", reason="SQL 中包含注释符号（--），疑似注入")

    # 检测块注释 /* */
    if "/*" in sql or "*/" in sql:
        return RuleResult(passed=False, rule_name="injection", reason="SQL 中包含块注释（/* */），疑似注入")

    # 检测多语句分隔符
    if ";" in sql:
        return RuleResult(passed=False, rule_name="injection", reason="SQL 中包含分号（;），禁止多语句执行")

    return None
