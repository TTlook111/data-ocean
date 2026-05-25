"""危险函数规则：黑名单函数检测"""

import sqlglot
from sqlglot import exp

from . import RuleResult

RULE_NAME = "function_rule"

_BLACKLIST = frozenset({
    "SLEEP", "BENCHMARK", "LOAD_FILE", "INTO_OUTFILE",
    "OUTFILE", "DUMPFILE", "SYSTEM", "EXEC",
})


def check(sql: str) -> RuleResult:
    """检查 SQL 中是否包含黑名单函数"""
    try:
        tree = sqlglot.parse_one(sql, dialect="mysql")
    except sqlglot.errors.ParseError:
        return RuleResult(passed=True, rule_name=RULE_NAME)

    for node in tree.walk():
        if isinstance(node, (exp.Anonymous, exp.Func)):
            func_name = ""
            if isinstance(node, exp.Anonymous):
                func_name = node.name.upper()
            elif hasattr(node, "sql_name"):
                func_name = node.sql_name().upper()
            elif hasattr(node, "key"):
                func_name = node.key.upper()

            if func_name in _BLACKLIST:
                return RuleResult(
                    passed=False,
                    rule_name=RULE_NAME,
                    reason=f"包含禁止的危险函数：{func_name}",
                )

    return RuleResult(passed=True, rule_name=RULE_NAME)
