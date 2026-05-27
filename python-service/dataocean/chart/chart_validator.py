"""ECharts Option JSON 校验器

校验 LLM 生成的 ECharts Option 是否合法可用。
"""

from __future__ import annotations

import logging

logger = logging.getLogger(__name__)


def validate_chart_option(option: dict | None) -> dict | None:
    """校验 ECharts Option 合法性

    校验规则：
    - 必须是 dict
    - 必须包含 series 字段且为非空列表
    - 非饼图必须包含 xAxis 或 yAxis
    - 饼图的 series.data 必须为列表

    Returns:
        合法的 option 原样返回，无效返回 None
    """
    if not option or not isinstance(option, dict):
        return None

    series = option.get("series")
    if not series or not isinstance(series, list) or len(series) == 0:
        logger.debug("校验失败：缺少 series 或为空")
        return None

    first_series = series[0]
    if not isinstance(first_series, dict):
        logger.debug("校验失败：series[0] 不是 dict")
        return None

    chart_type = first_series.get("type", "")

    if chart_type == "pie":
        data = first_series.get("data")
        if not data or not isinstance(data, list):
            logger.debug("校验失败：饼图缺少 series.data")
            return None
    else:
        if not option.get("xAxis") and not option.get("yAxis"):
            logger.debug("校验失败：非饼图缺少 xAxis/yAxis")
            return None

    return option
