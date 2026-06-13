"""表白名单规则：检查 SQL 中引用的表是否在授权范围内"""

import logging
import sqlglot
from sqlglot import exp

from . import RuleResult

logger = logging.getLogger(__name__)

RULE_NAME = "table_rule"

# 表访问范围模式
_TABLE_SCOPE_UNSPECIFIED = "UNSPECIFIED"
_TABLE_SCOPE_ALLOWLIST = "ALLOWLIST"
_TABLE_SCOPE_UNRESTRICTED = "UNRESTRICTED"


def check(
    sql: str,
    allowed_tables: list[str] | None = None,
    table_scope_mode: str = _TABLE_SCOPE_UNSPECIFIED,
) -> RuleResult:
    """检查 SQL 中引用的表是否都在白名单中

    Args:
        sql: SQL 语句
        allowed_tables: 允许访问的表列表
        table_scope_mode: 表访问范围模式
            - UNSPECIFIED: 未提供权限上下文，默认拒绝
            - ALLOWLIST: 使用白名单模式
            - UNRESTRICTED: 显式全库开放

    Returns:
        RuleResult 校验结果
    """
    # 向后兼容：如果未传递 table_scope_mode，但 allowed_tables 非空，
    # 视为 ALLOWLIST 模式（兼容旧版本 Java 端未传递 tableScopeMode 的情况）
    if table_scope_mode == _TABLE_SCOPE_UNSPECIFIED and allowed_tables:
        logger.info("向后兼容：table_scope_mode=UNSPECIFIED 但 allowed_tables 非空，视为 ALLOWLIST 模式")
        table_scope_mode = _TABLE_SCOPE_ALLOWLIST

    # 安全修复：未提供权限上下文时默认拒绝
    if table_scope_mode == _TABLE_SCOPE_UNSPECIFIED:
        logger.warning("表访问被拒绝：未提供权限上下文 (table_scope_mode=UNSPECIFIED)")
        return RuleResult(
            passed=False,
            rule_name=RULE_NAME,
            reason="未提供表访问权限上下文，请联系管理员配置数据源访问权限",
        )

    # 显式全库开放模式
    if table_scope_mode == _TABLE_SCOPE_UNRESTRICTED:
        return RuleResult(passed=True, rule_name=RULE_NAME)

    # 白名单模式：allowed_tables 为空时拒绝
    if not allowed_tables:
        logger.warning("表访问被拒绝：白名单模式但未配置允许的表")
        return RuleResult(
            passed=False,
            rule_name=RULE_NAME,
            reason="未配置允许访问的表列表，请联系管理员配置数据源访问权限",
        )

    try:
        tree = sqlglot.parse_one(sql, dialect="mysql")
    except sqlglot.errors.ParseError as e:
        return RuleResult(passed=False, rule_name=RULE_NAME, reason=f"SQL 语法解析失败：{e}")

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
