"""SQL 安全校验规则包

每条规则独立实现，返回 RuleResult 表示通过或失败。
"""

from dataclasses import dataclass


@dataclass
class RuleResult:
    """单条规则校验结果"""

    passed: bool
    rule_name: str
    reason: str = ""
