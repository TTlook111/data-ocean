"""语句类型规则：仅允许 SELECT 语句"""

import sqlglot
from sqlglot import exp

from . import RuleResult

RULE_NAME = "statement_rule"


def check(sql: str) -> RuleResult:
    """检查 SQL 是否为 SELECT 语句"""
    try:
        statements = sqlglot.parse(sql, dialect="mysql")
    except sqlglot.errors.ParseError as e:
        return RuleResult(passed=False, rule_name=RULE_NAME, reason=f"SQL 语法解析失败：{e}")

    if not statements:
        return RuleResult(passed=False, rule_name=RULE_NAME, reason="SQL 为空")

    if len(statements) > 1:
        return RuleResult(passed=False, rule_name=RULE_NAME, reason="禁止多语句执行")

    stmt = statements[0]
    if not isinstance(stmt, exp.Select):
        stmt_type = type(stmt).__name__
        return RuleResult(passed=False, rule_name=RULE_NAME, reason=f"仅允许 SELECT 语句，当前为 {stmt_type}")

    return RuleResult(passed=True, rule_name=RULE_NAME)
