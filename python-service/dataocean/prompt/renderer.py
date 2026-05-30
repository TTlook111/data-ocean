"""Prompt 模板渲染器

基于 LangChain PromptTemplate（jinja2 格式）渲染模板。
统一了原先「managed 模板用正则、本地模板用 jinja2」两套引擎：
- jinja2 格式对 {{var}} 占位符做无转义替换（保留 SQL/JSON 中的 < & 等特殊字符）；
- 同时支持 {% for %}/{% if %} 控制流，本地 .j2 模板可直接复用同一套渲染。

变量缺失时渲染为空字符串（jinja2 默认 Undefined 行为），多余变量被忽略。
"""

import hashlib
import logging
from pathlib import Path

from langchain_core.prompts import PromptTemplate

logger = logging.getLogger(__name__)

# 本地 .j2 模板文件的编译缓存（按路径缓存已解析的 PromptTemplate）
_file_template_cache: dict[str, PromptTemplate] = {}
# managed 模板内容的编译缓存（按内容 hash 缓存，避免同一模板重复编译 jinja2）
_content_template_cache: dict[str, PromptTemplate] = {}


def render_template(template_content: str, variables: dict[str, str]) -> str:
    """渲染 Prompt 模板

    将模板中的 {{variable}} 占位符替换为 variables 中对应的值，
    支持 jinja2 控制流。变量缺失渲染为空，多余变量忽略。
    对同一模板内容做 hash 缓存，避免重复编译。

    Args:
        template_content: 模板内容（jinja2 语法）
        variables: 变量名 → 值的字典

    Returns:
        渲染后的完整 Prompt 文本

    Raises:
        Exception: 模板语法非法时由 jinja2 抛出，交由上层决定是否降级
    """
    if not template_content:
        return ""
    # 按内容 hash 缓存编译后的 PromptTemplate
    cache_key = hashlib.md5(template_content.encode()).hexdigest()
    template = _content_template_cache.get(cache_key)
    if template is None:
        template = PromptTemplate.from_template(template_content, template_format="jinja2")
        _content_template_cache[cache_key] = template
    return template.format(**variables)


def render_template_file(template_path: Path | str, **variables) -> str:
    """渲染本地 .j2 模板文件（jinja2 格式，支持控制流与富对象）

    与 render_template 共用同一套 LangChain PromptTemplate 引擎，
    供各节点的本地降级模板使用，按路径缓存已编译模板。

    Args:
        template_path: 模板文件路径
        **variables: 模板变量（可为 dict/list 等富对象）

    Returns:
        渲染后的文本
    """
    key = str(template_path)
    template = _file_template_cache.get(key)
    if template is None:
        content = Path(template_path).read_text(encoding="utf-8")
        template = PromptTemplate.from_template(content, template_format="jinja2")
        _file_template_cache[key] = template
    return template.format(**variables)

