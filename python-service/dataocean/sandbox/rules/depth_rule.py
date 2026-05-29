"""子查询嵌套深度规则"""

import sqlglot
from sqlglot import exp

from . import RuleResult
from ..config import sandbox_config

RULE_NAME = "depth_rule"


def check(sql: str) -> RuleResult:
    """检查子查询嵌套深度是否超过限制"""
    try:
        tree = sqlglot.parse_one(sql, dialect="mysql")
    except sqlglot.errors.ParseError as e:
        return RuleResult(passed=False, rule_name=RULE_NAME, reason=f"SQL 语法解析失败：{e}")

    max_depth = _measure_depth(tree, 0)
    limit = sandbox_config.max_subquery_depth

    if max_depth > limit:
        return RuleResult(
            passed=False,
            rule_name=RULE_NAME,
            reason=f"子查询嵌套深度 {max_depth} 超过限制 {limit}",
        )

    return RuleResult(passed=True, rule_name=RULE_NAME)


def _measure_depth(node, current_depth: int) -> int:
    """递归计算子查询最大嵌套深度（UNION 中的平级 SELECT 不计入嵌套）"""
    max_depth = current_depth
    for child in node.iter_expressions():
        if isinstance(child, exp.Subquery):
            # Subquery 节点一定是嵌套
            child_depth = _measure_depth(child, current_depth + 1)
            max_depth = max(max_depth, child_depth)
        elif isinstance(child, exp.Select) and child is not node:
            # 仅当 SELECT 出现在 Subquery 内部时才计入嵌套，
            # UNION 中的平级 SELECT 不增加深度
            parent = child.parent
            if isinstance(parent, exp.Subquery):
                child_depth = _measure_depth(child, current_depth + 1)
            else:
                child_depth = _measure_depth(child, current_depth)
            max_depth = max(max_depth, child_depth)
        else:
            child_depth = _measure_depth(child, current_depth)
            max_depth = max(max_depth, child_depth)
    return max_depth
