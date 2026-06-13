"""危险函数规则：黑名单函数检测

检测 MySQL 中可能导致安全风险或性能问题的函数。
包括：文件操作、命令执行、XML 注入、锁函数、加密函数等。
"""

import sqlglot
from sqlglot import exp

from . import RuleResult

RULE_NAME = "function_rule"

# 危险函数黑名单
# 包含：文件操作、命令执行、XML 注入、锁函数、加密函数等
_BLACKLIST = frozenset({
    # 文件操作
    "LOAD_FILE", "INTO_OUTFILE", "OUTFILE", "DUMPFILE",
    # 命令执行
    "SYSTEM", "EXEC",
    # 性能影响
    "SLEEP", "BENCHMARK",
    # XML 注入（可用于报错注入）
    "UPDATEXML", "EXTRACTVALUE",
    # 锁函数（可能导致阻塞）
    "GET_LOCK", "RELEASE_LOCK", "RELEASE_ALL_LOCKS", "IS_FREE_LOCK", "IS_USED_LOCK",
    # 加密函数（可能泄露信息）
    "AES_ENCRYPT", "AES_DECRYPT",
    # 会话/系统信息泄露
    "CURRENT_USER", "SESSION_USER", "SYSTEM_USER", "DATABASE", "SCHEMA",
    # 文件权限相关
    "GRANT", "REVOKE",
})


def check(sql: str) -> RuleResult:
    """检查 SQL 中是否包含黑名单函数"""
    try:
        tree = sqlglot.parse_one(sql, dialect="mysql")
    except sqlglot.errors.ParseError as e:
        return RuleResult(passed=False, rule_name=RULE_NAME, reason=f"SQL 语法解析失败：{e}")

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
