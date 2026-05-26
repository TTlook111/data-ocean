"""Prompt 模板渲染器

使用 Jinja2 风格的 {{变量}} 占位符渲染模板。
变量不存在时跳过并记录 warning 日志。
"""

import logging
import re

logger = logging.getLogger(__name__)

# 匹配 {{variable_name}} 占位符
VARIABLE_PATTERN = re.compile(r"\{\{(\w+)\}\}")


def render_template(template_content: str, variables: dict[str, str]) -> str:
    """渲染 Prompt 模板

    将模板中的 {{variable_name}} 替换为 variables 中对应的值。
    如果变量不存在，保留原始占位符并记录 warning。

    Args:
        template_content: 模板内容（含 {{变量}} 占位符）
        variables: 变量名 → 值的字典

    Returns:
        渲染后的完整 Prompt 文本
    """
    if not template_content:
        return ""

    def replace_variable(match: re.Match) -> str:
        var_name = match.group(1)
        if var_name in variables and variables[var_name] is not None:
            return variables[var_name]
        logger.warning("Prompt 模板变量未提供: {{%s}}，保留占位符", var_name)
        return match.group(0)

    return VARIABLE_PATTERN.sub(replace_variable, template_content)


def extract_variables(template_content: str) -> list[str]:
    """提取模板中的所有变量名

    Args:
        template_content: 模板内容

    Returns:
        变量名列表（去重）
    """
    if not template_content:
        return []
    return list(set(VARIABLE_PATTERN.findall(template_content)))
