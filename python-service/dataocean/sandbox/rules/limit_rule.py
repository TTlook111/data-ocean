"""LIMIT 规则：检查 LIMIT 值是否超过上限"""

import sqlglot
from sqlglot import exp

from . import RuleResult
from ..config import sandbox_config

RULE_NAME = "limit_rule"


def check(sql: str) -> RuleResult:
    """检查 SQL 中所有 LIMIT 值是否超过最大行数限制

    必须遍历全部 LIMIT 节点：子查询、派生表和 UNION 分支各自可能有自己的
    LIMIT，只看第一个节点会漏掉超限的分支。
    """
    try:
        tree = sqlglot.parse_one(sql, dialect="mysql")
    except sqlglot.errors.ParseError as e:
        return RuleResult(passed=False, rule_name=RULE_NAME, reason=f"SQL 语法解析失败：{e}")

    for limit_node in tree.find_all(exp.Limit):
        limit_expr = limit_node.expression
        if not isinstance(limit_expr, exp.Literal) or limit_expr.is_string:
            continue
        try:
            limit_value = int(limit_expr.this)
        except (ValueError, TypeError):
            continue
        if limit_value > sandbox_config.max_result_rows:
            return RuleResult(
                passed=False,
                rule_name=RULE_NAME,
                reason=f"LIMIT {limit_value} 超过最大限制 {sandbox_config.max_result_rows}",
            )

    return RuleResult(passed=True, rule_name=RULE_NAME)
