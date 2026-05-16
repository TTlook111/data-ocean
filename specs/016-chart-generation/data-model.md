# Data Model: 图表生成与结果解释模块

## Overview

图表生成模块不引入新的持久化表。图表配置是查询响应的一部分，随查询结果一起返回，不单独存储。

## Runtime Data Structures

### ChartGenerationInput (Python)

传给 Data_Visualizer_Node 的输入：

```python
@dataclass
class ChartGenerationInput:
    question: str                    # 用户原始问题
    data_preview: list[dict]         # 查询结果前 20 行
    column_types: dict[str, str]     # 列名 → SQL 类型
    total_rows: int                  # 总行数
```

### ChartGenerationOutput (Python)

Data_Visualizer_Node 的输出：

```python
@dataclass
class ChartGenerationOutput:
    chart_type: str | None           # bar/line/pie/None
    echarts_option: dict | None      # ECharts Option JSON
    suggested_types: list[str]       # 可切换的图表类型列表
    aggregated: bool                 # 是否经过聚合
    aggregation_note: str | None     # 聚合说明
    reason: str | None               # 不生成图表的原因
```

### QueryExplanation (Python → Java → Frontend)

口径说明，随查询结果一起返回：

```python
@dataclass
class QueryExplanation:
    tables_used: list[str]           # 使用的表
    columns_used: list[ColumnUsage]  # 使用的列及可信度
    low_confidence_warning: bool     # 是否有低可信字段
    data_scope: str                  # 数据范围描述

@dataclass
class ColumnUsage:
    table: str
    column: str
    confidence: int                  # 0-100
    definition: str | None           # 字段业务定义（来自 skills.md）
```

## Chart Type Decision Matrix

| 数据特征 | 推荐图表 | 条件 |
|----------|----------|------|
| 时间维度 + 数值 | line | 时间列为 DATE/DATETIME/TIMESTAMP |
| 分类维度 + 数值 | bar | 分类数 ≤ 10 |
| 分类维度 + 数值 | bar (horizontal) | 分类数 > 10 且 ≤ 20 |
| 占比/百分比 | pie | SUM ≈ 100% 或字段名含 ratio/percent |
| 单行单列 | null (纯数值) | 结果只有 1 行 1 列 |
| 多维度交叉 | bar (grouped) | 2 个分类维度 + 1 个数值 |

## Aggregation Rules

| 场景 | 聚合策略 | 示例 |
|------|----------|------|
| 时间序列 > 50 行 | 提升时间粒度 | 365 天 → 12 月 |
| 分类 > 10 类 | Top 10 + 其他 | 50 个城市 → Top 10 + 其他 |
| 数值 > 50 行 | 等距分桶 | 1000 条记录 → 10 个区间 |

## Frontend State

```typescript
interface ChartState {
  chartType: 'bar' | 'line' | 'pie' | null
  echartsOption: Record<string, any> | null
  suggestedTypes: string[]
  aggregated: boolean
  aggregationNote: string | null
}
```

前端不持久化图表状态，每次查询重新生成。用户切换图表类型时，前端本地修改 `echartsOption.series[].type` 和相关轴配置。
