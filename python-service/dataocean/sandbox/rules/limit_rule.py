"""LIMIT 规则：检查 LIMIT 值是否超过上限"""

import sqlglot
from sqlglot import exp

from . import RuleResult
from ..config import sandbox_config

RULE_NAME = "limit_rule"


def check(sql: str) -> RuleResult:
    """检查 SQL 中 LIMIT 值是否超过最大行数限制"""
    try:
        tree = sqlglot.parse_one(sql, dialect="mysql")
    except sqlglot.errors.ParseError as e:
        return RuleResult(passed=False, rule_name=RULE_NAME, reason=f"SQL 语法解析失败：{e}")

    limit_node = tree.find(exp.Limit)
    if limit_node is None:
        return RuleResult(passed=True, rule_name=RULE_NAME)

    try:
        limit_expr = limit_node.expression
        if isinstance(limit_expr, exp.Literal):
            limit_value = int(limit_expr.this)
            if limit_value > sandbox_config.max_result_rows:
                return RuleResult(
                    passed=False,
                    rule_name=RULE_NAME,
                    reason=f"LIMIT {limit_value} 超过最大限制 {sandbox_config.max_result_rows}",
                )
    except (ValueError, TypeError):
        pass

    return RuleResult(passed=True, rule_name=RULE_NAME)
