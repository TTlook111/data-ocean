# Implementation Plan: 图表生成与结果解释模块

**Branch**: `016-chart-generation` | **Date**: 2026-05-16 | **Spec**: [spec.md](spec.md)

## Summary

图表生成模块在 Python LangGraph 的 Data_Visualizer_Node 中，由 LLM 根据查询结果的数据特征自动推荐图表类型并生成 ECharts Option JSON。前端接收 Option 后渲染图表，支持类型切换和 PNG 导出。

## Technical Context

**Language/Version**: Python 3.13 (FastAPI + LangGraph) + Vue 3 (TypeScript)

**Primary Dependencies**:
- Python: LangGraph, Qwen LLM API, json schema validation
- Frontend: ECharts 5.x, Vue 3 + TypeScript

**Testing**: pytest (Python), Vitest (Frontend)

**Constraints**: 传给 LLM 的数据最多 20 行；超 50 行先聚合；ECharts Option 必须为合法 JSON

## Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. 元数据治理优先 | N/A | 图表模块不涉及元数据 |
| II. SQL 安全与只读执行 | N/A | 图表模块在 SQL 执行之后 |
| III. 三层分离架构 | PASS | Python 生成 Option，前端渲染 |
| IV. RAG 准入控制 | N/A | 不涉及 RAG |
| V. 可信度驱动生成 | PASS | 口径说明中展示字段可信度 |
| VI. 渐进式 MVP | PASS | 4 种基本图表类型 |

**Gate Result**: PASS

## Project Structure

```text
python-service/dataocean/chart/
├── router.py                  # /internal/chart/generate
├── service.py                 # 图表生成主逻辑
├── data_aggregator.py         # 数据聚合（>50行时）
├── chart_validator.py         # ECharts Option JSON 校验
└── prompts/                   # chart_generation prompt 模板（从 Prompt 管理模块获取）

frontend/src/components/chart/
├── ChartContainer.vue         # 图表容器（ECharts 实例管理）
├── ChartTypeSwitcher.vue      # 图表类型切换按钮组
├── ChartExporter.vue          # PNG 导出按钮
└── QueryExplanation.vue       # 口径说明展示

frontend/src/composables/
└── useChart.ts                # 图表相关逻辑 composable
```

## Implementation Phases

### Phase 1: Python Data_Visualizer_Node

1. 实现 LangGraph Data_Visualizer_Node
2. 数据预处理：取前 20 行 + 列类型推断 + 总行数
3. 调用 LLM（chart_generation prompt）生成 ECharts Option
4. JSON 校验：解析 LLM 输出，验证为合法 ECharts Option
5. 失败降级：JSON 无效时返回 chart_type=null，前端展示纯表格

### Phase 2: 数据聚合逻辑

1. 实现 data_aggregator.py：
   - 时间序列 > 50 行：按时间粒度聚合（日→周→月）
   - 分类数据 > 10 类：保留 Top 10 + "其他"
   - 数值数据 > 50 行：分桶统计
2. 聚合后再传给 LLM 生成图表配置

### Phase 3: 前端 ECharts 渲染

1. ChartContainer.vue：接收 ECharts Option，初始化和销毁 ECharts 实例
2. ChartTypeSwitcher.vue：切换图表类型（前端重新映射 series type）
3. ChartExporter.vue：调用 ECharts saveAsImage 导出 PNG
4. 响应式适配：窗口 resize 时自动调整图表尺寸

### Phase 4: 口径说明

1. QueryExplanation.vue：展示使用的表、字段、可信度来源
2. 数据来自 Python 查询响应中的 explanation 字段
3. 低可信字段标注警告提示

## Key Design Decisions

- **LLM 生成 Option 而非数据**: LLM 只生成 ECharts 配置结构（series type, axis config），实际数据由前端绑定
- **前端类型切换**: 切换图表类型时不重新调用 LLM，前端直接修改 series.type 和相关配置
- **降级策略**: ECharts Option 解析失败 → 纯表格展示，不阻塞用户获取数据
- **数据量控制**: 传给 LLM 的永远是前 20 行预览 + 列类型元信息，不传全量数据
- **聚合时机**: 数据 > 50 行时在 Python 层聚合后再生成图表，避免前端渲染性能问题
