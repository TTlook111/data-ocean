"""数据聚合模块

对大数据量进行聚合处理，确保传给 LLM 和前端渲染的数据量可控。
"""

from __future__ import annotations

import logging
from collections import defaultdict
from datetime import datetime

logger = logging.getLogger(__name__)

_MAX_CATEGORIES = 10
_MAX_ROWS_BEFORE_AGGREGATE = 50


def aggregate_for_chart(
    data: list[dict],
    column_types: dict[str, str],
    total_rows: int,
) -> tuple[list[dict], str | None]:
    """对数据进行聚合处理

    Args:
        data: 原始数据行
        column_types: 列名→类型映射
        total_rows: 总行数

    Returns:
        (聚合后的数据, 聚合说明文本) 或 (原始数据, None) 如果不需要聚合
    """
    if total_rows <= _MAX_ROWS_BEFORE_AGGREGATE:
        return data, None

    time_col = _find_time_column(column_types)
    category_col = _find_category_column(column_types, data)
    numeric_cols = _find_numeric_columns(column_types)

    if time_col and numeric_cols:
        return _aggregate_time_series(data, time_col, numeric_cols[0], total_rows)

    if category_col and numeric_cols:
        return _aggregate_categories(data, category_col, numeric_cols[0], total_rows)

    if numeric_cols and len(numeric_cols) >= 1:
        return _aggregate_buckets(data, numeric_cols[0], total_rows)

    return data[:_MAX_ROWS_BEFORE_AGGREGATE], f"数据量较大（{total_rows} 行），仅展示前 {_MAX_ROWS_BEFORE_AGGREGATE} 行"


def _find_time_column(column_types: dict[str, str]) -> str | None:
    """查找时间类型列"""
    time_types = {"DATE", "DATETIME", "TIMESTAMP", "TIME"}
    for col, col_type in column_types.items():
        if col_type.upper() in time_types:
            return col
    for col in column_types:
        if any(kw in col.lower() for kw in ("date", "time", "day", "month", "year")):
            return col
    return None


def _find_category_column(column_types: dict[str, str], data: list[dict]) -> str | None:
    """查找分类列（字符串类型且值种类有限）"""
    str_types = {"VARCHAR", "CHAR", "TEXT", "ENUM"}
    for col, col_type in column_types.items():
        if col_type.upper() in str_types or col_type.upper().startswith("VARCHAR"):
            unique_values = set(row.get(col) for row in data[:100] if row.get(col) is not None)
            if 2 <= len(unique_values) <= 50:
                return col
    return None


def _find_numeric_columns(column_types: dict[str, str]) -> list[str]:
    """查找数值类型列"""
    numeric_types = {"INT", "INTEGER", "BIGINT", "FLOAT", "DOUBLE", "DECIMAL", "NUMERIC", "TINYINT", "SMALLINT"}
    result = []
    for col, col_type in column_types.items():
        if col_type.upper() in numeric_types or col_type.upper().startswith(("INT", "DECIMAL", "FLOAT", "DOUBLE")):
            result.append(col)
    return result


def _aggregate_time_series(
    data: list[dict], time_col: str, value_col: str, total_rows: int
) -> tuple[list[dict], str]:
    """时间序列聚合：按月分组求和"""
    monthly: dict[str, float] = defaultdict(float)
    for row in data:
        time_val = row.get(time_col)
        if time_val is None:
            continue
        month_key = _extract_month(time_val)
        if month_key:
            val = row.get(value_col, 0)
            monthly[month_key] += float(val) if val else 0

    aggregated = [{time_col: k, value_col: v} for k, v in sorted(monthly.items())]
    note = f"数据已按月聚合（原始 {total_rows} 行 → {len(aggregated)} 行）"
    return aggregated, note


def _extract_month(value) -> str | None:
    """从时间值中提取年月"""
    if isinstance(value, datetime):
        return value.strftime("%Y-%m")
    s = str(value)
    if len(s) >= 7:
        return s[:7]
    return None


def _aggregate_categories(
    data: list[dict], category_col: str, value_col: str, total_rows: int
) -> tuple[list[dict], str]:
    """分类聚合：保留 Top N + 其他

    算法：
    1. 按分类列分组，累加数值列
    2. 按累加值降序排序
    3. 保留前 N-1 个分类，其余合并为"其他"
    """
    # 按分类列分组，累加数值列
    totals: dict[str, float] = defaultdict(float)
    for row in data:
        cat = row.get(category_col, "未知")
        val = row.get(value_col, 0)
        totals[str(cat)] += float(val) if val else 0

    # 按累加值降序排序
    sorted_items = sorted(totals.items(), key=lambda x: x[1], reverse=True)
    if len(sorted_items) <= _MAX_CATEGORIES:
        # 分类数未超限，直接返回
        aggregated = [{category_col: k, value_col: v} for k, v in sorted_items]
    else:
        # 分类数超限，保留 Top N-1，其余合并为"其他"
        top = sorted_items[:_MAX_CATEGORIES - 1]
        others_sum = sum(v for _, v in sorted_items[_MAX_CATEGORIES - 1:])
        aggregated = [{category_col: k, value_col: v} for k, v in top]
        aggregated.append({category_col: "其他", value_col: others_sum})

    note = f"分类数据已聚合（原始 {len(totals)} 类 → {len(aggregated)} 类）"
    return aggregated, note


def _aggregate_buckets(
    data: list[dict], value_col: str, total_rows: int
) -> tuple[list[dict], str]:
    """数值分桶：等距 N 个桶

    算法：
    1. 计算数值列的最小值和最大值
    2. 等距划分为 N 个桶
    3. 统计每个桶的数量
    """
    values = [float(row.get(value_col, 0)) for row in data if row.get(value_col) is not None]
    if not values:
        return data[:_MAX_ROWS_BEFORE_AGGREGATE], None

    min_val, max_val = min(values), max(values)
    if min_val == max_val:
        # 所有值相同，无需分桶
        return [{value_col: min_val, "count": len(values)}], f"所有值相同（{min_val}）"

    bucket_count = 10
    bucket_size = (max_val - min_val) / bucket_count
    buckets: list[int] = [0] * bucket_count

    for v in values:
        idx = min(int((v - min_val) / bucket_size), bucket_count - 1)
        buckets[idx] += 1

    aggregated = []
    for i in range(bucket_count):
        low = min_val + i * bucket_size
        high = low + bucket_size
        label = f"{low:.0f}-{high:.0f}"
        aggregated.append({"range": label, "count": buckets[i]})

    note = f"数值数据已分桶统计（原始 {total_rows} 行 → {bucket_count} 桶）"
    return aggregated, note

