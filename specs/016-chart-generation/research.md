# Research: 图表生成与结果解释模块

## ECharts Option 生成方案

**Decision**: LLM 生成完整 ECharts Option JSON，前端直接 setOption 渲染

**Rationale**: ECharts Option 是声明式配置，LLM 擅长生成结构化 JSON。前端无需理解数据语义，只需将 Option 传给 ECharts 实例即可渲染。

**Alternatives considered**:
- 前端规则引擎自动选图: 规则有限，无法处理复杂场景（如多维度交叉）
- LLM 只返回图表类型，前端生成 Option: 前端逻辑复杂，且无法利用 LLM 的数据理解能力
- 使用 Vega-Lite 规范: 生态不如 ECharts 丰富，中文支持弱

## 图表类型推荐规则

**Decision**: LLM 基于数据特征推荐，辅以规则兜底

**Rules** (LLM prompt 中的指导规则):
- 时间维度 + 数值 → 折线图 (line)
- 分类维度 + 数值（≤10 类）→ 柱状图 (bar)
- 占比/百分比数据 → 饼图 (pie)
- 单行单列 → 不生成图表，纯数值展示
- 多维度交叉 → 分组柱状图或堆叠图

**Fallback**: 如果 LLM 推荐的类型不在支持列表中，降级为柱状图。

## 数据聚合策略

**Decision**: Python 层在传给 LLM 之前执行聚合

**Rationale**: 大数据量直接生成图表会导致：(1) LLM 输入过长；(2) 前端渲染卡顿；(3) 图表可读性差。在 Python 层聚合后，数据量可控。

**Aggregation rules**:
- 时间序列 > 50 行: 自动提升时间粒度（日→周→月→季→年）
- 分类 > 10 类: 保留 Top 10（按数值降序），其余合并为"其他"
- 纯数值 > 50 行: 等距分桶（10 个桶）

## ECharts Option 校验方案

**Decision**: JSON Schema 基础校验 + try-catch 渲染

**Rationale**: ECharts Option 结构灵活，完整 schema 校验不现实。采用两层校验：
1. Python 层: 验证是合法 JSON，且包含必要字段（series, xAxis/yAxis 或 legend）
2. 前端层: try-catch 包裹 setOption，渲染失败则降级为表格

**Alternatives considered**:
- 完整 JSON Schema 校验: ECharts Option 太灵活，schema 维护成本高
- 不校验直接渲染: 无效 JSON 会导致前端报错

## 前端图表类型切换方案

**Decision**: 前端本地修改 series.type，不重新调用 LLM

**Rationale**: 类型切换是轻量操作，用户期望即时响应。重新调用 LLM 需要 2-5 秒，体验差。前端只需修改 series[0].type 和相关轴配置即可。

**Type switch mapping**:
- bar ↔ line: 直接切换 type，轴配置不变
- bar/line → pie: 重构为 {name, value} 数组，移除轴配置
- pie → bar/line: 从 pie data 还原为轴 + series 结构

## 口径说明数据来源

**Decision**: Python 查询响应中附带 explanation 字段

**Rationale**: 口径说明的数据（使用的表、字段、可信度）在 Python 查询流程中自然产生（RAG 召回结果 + SQL 解析结果），无需额外调用。

**Explanation structure**:
```json
{
  "tables_used": ["orders", "customers"],
  "columns_used": [
    {"table": "orders", "column": "pay_amount", "confidence": 95, "definition": "实际支付金额"}
  ],
  "low_confidence_warning": false,
  "data_scope": "2026-04-01 至 2026-04-30"
}
```
