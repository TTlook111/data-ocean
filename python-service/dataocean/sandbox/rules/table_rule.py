"""表白名单规则：检查 SQL 中引用的表是否在授权范围内"""

import sqlglot
from sqlglot import exp

from . import RuleResult

RULE_NAME = "table_rule"


def check(sql: str, allowed_tables: list[str] | None = None) -> RuleResult:
    """检查 SQL 中引用的表是否都在白名单中"""
    if not allowed_tables:
        return RuleResult(passed=True, rule_name=RULE_NAME)

    try:
        tree = sqlglot.parse_one(sql, dialect="mysql")
    except sqlglot.errors.ParseError:
        return RuleResult(passed=True, rule_name=RULE_NAME)

    allowed_set = {t.lower() for t in allowed_tables}
    referenced_tables = set()

    for table_node in tree.find_all(exp.Table):
        table_name = table_node.name.lower()
        if table_name:
            referenced_tables.add(table_name)

    unauthorized = referenced_tables - allowed_set
    if unauthorized:
        return RuleResult(
            passed=False,
            rule_name=RULE_NAME,
            reason=f"引用了未授权的表：{', '.join(sorted(unauthorized))}",
        )

    return RuleResult(passed=True, rule_name=RULE_NAME)
