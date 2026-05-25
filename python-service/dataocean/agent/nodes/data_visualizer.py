"""图表生成节点

基于查询结果调用 LLM 生成 ECharts option 配置。
数据行数为 0 或仅 1 行 1 列时跳过图表生成。
"""

from __future__ import annotations

import json
import logging
import re
from pathlib import Path

from jinja2 import Template

from ..llm import call_llm
from ..state import AgentState

logger = logging.getLogger(__name__)

_PROMPTS_DIR = Path(__file__).parent.parent / "prompts"
_template_cache: Template | None = None

_MAX_SAMPLE_ROWS = 10


def _get_template() -> Template:
    """获取缓存的图表生成 Prompt 模板"""
    global _template_cache
    if _template_cache is None:
        path = _PROMPTS_DIR / "visualization.j2"
        _template_cache = Template(path.read_text(encoding="utf-8"))
    return _template_cache


def _extract_json(text: str) -> dict | None:
    """从 LLM 响应中提取 JSON"""
    text = text.strip()
    if text.lower() == "null":
        return None
    # 尝试匹配 ```json ... ```
    match = re.search(r"```(?:json)?\s*\n?(.*?)\n?```", text, re.DOTALL)
    if match:
        content = match.group(1).strip()
        if content.lower() == "null":
            return None
        return json.loads(content)
    # 直接解析
    if text.startswith("{"):
        return json.loads(text)
    return None


async def run_data_visualizer(state: AgentState) -> AgentState:
    """生成图表配置：填充模板 → 调用 LLM → 解析 ECharts option"""
    execution_result = state.get("execution_result", {})
    row_count = execution_result.get("row_count", 0)
    columns = execution_result.get("columns", [])
    data_rows = execution_result.get("data_rows", [])
    task_id = state.get("task_id", "")

    logger.info("图表生成 task_id=%s row_count=%d", task_id, row_count)

    # 无数据或单值不生成图表
    if row_count == 0:
        return {**state, "chart_config": None, "current_node": "DATA_VISUALIZER"}
    if row_count == 1 and len(columns) == 1:
        return {**state, "chart_config": None, "current_node": "DATA_VISUALIZER"}

    template = _get_template()
    sample_rows = data_rows[:_MAX_SAMPLE_ROWS]
    prompt = template.render(
        columns=columns,
        sample_rows=sample_rows,
        total_rows=row_count,
    )

    try:
        response_text = await call_llm(
            system_prompt="你是一个数据可视化专家。请根据数据特征输出 ECharts option JSON。",
            user_prompt=prompt,
            temperature=0.2,
        )
        chart_config = _extract_json(response_text)
    except Exception as e:
        logger.warning("图表生成失败 task_id=%s error=%s，跳过图表", task_id, e)
        chart_config = None

    # 生成推荐追问
    suggested = await _generate_suggestions(state)

    return {
        **state,
        "chart_config": chart_config,
        "suggested_questions": suggested,
        "current_node": "DATA_VISUALIZER",
    }


async def _generate_suggestions(state: AgentState) -> list[str]:
    """基于当前查询结果生成 2-3 个推荐追问"""
    question = state.get("question", "")
    used_tables = state.get("used_tables", [])

    prompt = (
        f"用户刚才问了：{question}\n"
        f"查询涉及的表：{', '.join(used_tables)}\n"
        f"请基于这个查询场景，生成 2-3 个用户可能感兴趣的追问问题。\n"
        f"直接输出问题列表，每行一个，不要编号和其他内容。"
    )

    try:
        response = await call_llm(
            system_prompt="你是一个数据分析助手。请生成简短的追问建议。",
            user_prompt=prompt,
            temperature=0.7,
        )
        lines = [line.strip() for line in response.strip().split("\n") if line.strip()]
        return lines[:3]
    except Exception as e:
        logger.debug("推荐追问生成失败: %s", e)
        return []
