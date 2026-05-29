"""图表生成服务

提供独立的图表生成能力，供 HTTP 端点和 LangGraph 节点调用。
"""

from __future__ import annotations

import json
import logging
import re

from dataocean.agent.llm import call_llm
from dataocean.prompt.service import render_prompt_with_metadata

from .chart_validator import validate_chart_option
from .data_aggregator import aggregate_for_chart

logger = logging.getLogger(__name__)

_MAX_PREVIEW_ROWS = 20

_SYSTEM_PROMPT = "你是一个数据可视化专家。请根据数据特征输出 ECharts option JSON。"

_USER_PROMPT_TEMPLATE = """根据以下查询结果数据，生成合适的 ECharts 图表配置。

## 用户问题
{question}

## 数据列信息
{columns_info}

## 数据样本（前 {sample_count} 行，共 {total_rows} 行）
{data_preview}

## 输出要求

选择最合适的图表类型，直接输出 ECharts option JSON：
- 时间序列数据 → 折线图 (line)
- 分类对比数据（≤10类）→ 柱状图 (bar)
- 占比/构成数据 → 饼图 (pie)
- 单个数值 → 输出 null

JSON 格式：
{{"title":{{"text":"标题"}},"xAxis":{{"type":"category","data":[...]}},"yAxis":{{"type":"value"}},"series":[{{"type":"bar","data":[...]}}]}}

如果数据不适合图表，输出 null。"""


class ChartResult:
    """图表生成结果"""

    def __init__(
        self,
        chart_type: str | None = None,
        echarts_option: dict | None = None,
        suggested_types: list[str] | None = None,
        aggregated: bool = False,
        aggregation_note: str | None = None,
        reason: str | None = None,
        prompt_version_no: int = 0,
    ):
        self.chart_type = chart_type
        self.echarts_option = echarts_option
        self.suggested_types = suggested_types or []
        self.aggregated = aggregated
        self.aggregation_note = aggregation_note
        self.reason = reason
        self.prompt_version_no = prompt_version_no

    def to_dict(self) -> dict:
        result = {
            "chart_type": self.chart_type,
            "echarts_option": self.echarts_option,
            "suggested_types": self.suggested_types,
            "aggregated": self.aggregated,
        }
        if self.aggregation_note:
            result["aggregation_note"] = self.aggregation_note
        if self.reason:
            result["reason"] = self.reason
        result["prompt_version_no"] = self.prompt_version_no
        return result


async def generate_chart(
    question: str,
    data_preview: list[dict],
    column_types: dict[str, str],
    total_rows: int,
) -> ChartResult:
    """生成 ECharts 图表配置

    Args:
        question: 用户原始问题
        data_preview: 数据行（LangGraph 节点传入全量数据用于聚合，HTTP 端点传入预览数据）
        column_types: 列名→SQL类型映射
        total_rows: 总行数

    Returns:
        ChartResult 包含图表类型、ECharts Option 等
    """
    if total_rows == 0:
        return ChartResult(reason="无数据")

    if total_rows == 1 and len(column_types) == 1:
        return ChartResult(reason="单行单列数据不适合图表展示")

    # 数据聚合
    aggregated = False
    aggregation_note = None
    chart_data = data_preview[:_MAX_PREVIEW_ROWS]

    if total_rows > 50:
        chart_data, aggregation_note = aggregate_for_chart(data_preview, column_types, total_rows)
        aggregated = aggregation_note is not None

    # 构建 LLM prompt
    columns_info = "\n".join(f"- {col} ({ctype})" for col, ctype in column_types.items())
    sample = chart_data[:_MAX_PREVIEW_ROWS]
    data_str = "\n".join(str(row) for row in sample)

    user_prompt = _USER_PROMPT_TEMPLATE.format(
        question=question,
        columns_info=columns_info,
        sample_count=len(sample),
        total_rows=total_rows,
        data_preview=data_str,
    )
    prompt_version_no = 0
    try:
        managed_prompt, prompt_version_no = await render_prompt_with_metadata(
            "chart_generation",
            {
                "question": question,
                "columns": columns_info,
                "data": data_str,
            },
        )
        if managed_prompt:
            user_prompt = managed_prompt
    except Exception as e:
        logger.debug("图表 Prompt 管理模板不可用，使用本地模板降级: %s", e)

    # 调用 LLM
    try:
        response_text = await call_llm(
            system_prompt=_SYSTEM_PROMPT,
            user_prompt=user_prompt,
            temperature=0.2,
        )
        option = _extract_json(response_text)
    except Exception as e:
        logger.warning("图表生成 LLM 调用失败: %s", e)
        return ChartResult(reason="图表配置生成失败，请查看表格数据", prompt_version_no=prompt_version_no)

    # 校验
    validated = validate_chart_option(option)
    if validated is None:
        return ChartResult(reason="图表配置生成失败，请查看表格数据", prompt_version_no=prompt_version_no)

    chart_type = _detect_chart_type(validated)
    suggested = _suggest_types(chart_type)

    return ChartResult(
        chart_type=chart_type,
        echarts_option=validated,
        suggested_types=suggested,
        aggregated=aggregated,
        aggregation_note=aggregation_note,
        prompt_version_no=prompt_version_no,
    )


def _extract_json(text: str) -> dict | None:
    """从 LLM 响应中提取 JSON"""
    text = text.strip()
    if text.lower() == "null":
        return None
    match = re.search(r"```(?:json)?\s*\n?(.*?)\n?```", text, re.DOTALL)
    if match:
        content = match.group(1).strip()
        if content.lower() == "null":
            return None
        return json.loads(content)
    if text.startswith("{"):
        return json.loads(text)
    return None


def _detect_chart_type(option: dict) -> str:
    """从 ECharts Option 中检测图表类型"""
    series = option.get("series", [])
    if series and isinstance(series[0], dict):
        return series[0].get("type", "bar")
    return "bar"


def _suggest_types(current_type: str) -> list[str]:
    """根据当前类型推荐可切换的类型"""
    all_types = ["bar", "line", "pie"]
    suggested = [current_type]
    for t in all_types:
        if t != current_type:
            suggested.append(t)
    return suggested
