"""SELECT * 规则：禁止使用 SELECT *"""

import sqlglot
from sqlglot import exp

from . import RuleResult

RULE_NAME = "star_rule"


def check(sql: str) -> RuleResult:
    """检查 SQL 是否包含 SELECT *"""
    try:
        tree = sqlglot.parse_one(sql, dialect="mysql")
    except sqlglot.errors.ParseError:
        return RuleResult(passed=True, rule_name=RULE_NAME)

    for node in tree.find_all(exp.Star):
        return RuleResult(
            passed=False,
            rule_name=RULE_NAME,
            reason="禁止 SELECT *，请指定具体字段",
        )

    return RuleResult(passed=True, rule_name=RULE_NAME)
