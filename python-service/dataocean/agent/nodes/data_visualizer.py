"""图表生成节点

基于查询结果调用 chart/service 生成 ECharts option 配置。
数据行数为 0 或仅 1 行 1 列时跳过图表生成。
"""

from __future__ import annotations

import logging

from dataocean.chart.service import generate_chart
from dataocean.infra.llm import call_llm
from dataocean.infra.parsers import LinesOutputParser

from ..prompt_tracking import record_prompt_version
from ..state import AgentState

logger = logging.getLogger(__name__)

# 把 LLM 返回的追问文本按行拆成最多 3 条
_suggestions_parser = LinesOutputParser(max_items=3)


async def run_data_visualizer(state: AgentState) -> AgentState:
    """生成图表配置：委托给 chart/service.generate_chart"""
    execution_result = state.get("execution_result", {})
    row_count = execution_result.get("row_count", 0)
    columns = execution_result.get("columns", [])
    data_rows = execution_result.get("data_rows", [])
    task_id = state.get("task_id", "")
    question = state.get("question", "")

    logger.info("图表生成 task_id=%s row_count=%d", task_id, row_count)

    # 无数据或单值不生成图表
    if row_count == 0:
        # TODO: followup_suggestions 尚未接入 Prompt 管理模板
        state = record_prompt_version(state, "followup_suggestions", 0)
        suggested = await _generate_suggestions(state)
        return {**state, "chart_config": None, "suggested_questions": suggested, "current_node": "DATA_VISUALIZER"}
    if row_count == 1 and len(columns) == 1:
        state = record_prompt_version(state, "followup_suggestions", 0)
        suggested = await _generate_suggestions(state)
        return {**state, "chart_config": None, "suggested_questions": suggested, "current_node": "DATA_VISUALIZER"}

    # 构建列类型映射
    column_types = {}
    for col in columns:
        if isinstance(col, dict):
            column_types[col.get("name", "")] = col.get("type", "VARCHAR")

    # 委托给 chart service（传入全量数据，service 内部处理聚合和截断）
    result = await generate_chart(
        question=question,
        data_preview=data_rows,
        column_types=column_types,
        total_rows=row_count,
    )
    state = record_prompt_version(state, "chart_generation", result.prompt_version_no)

    chart_config = result.echarts_option
    # TODO: followup_suggestions 尚未接入 Prompt 管理模板
    state = record_prompt_version(state, "followup_suggestions", 0)
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
        return _suggestions_parser.parse(response)
    except Exception as e:
        logger.debug("推荐追问生成失败: %s", e)
        return []
