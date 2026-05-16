# Tasks: 图表生成与结果解释模块

**Input**: Design documents from `specs/016-chart-generation/`
**Prerequisites**: plan.md, spec.md

## Phase 1: Setup

- [ ] T001 创建 Python 图表模块包结构 `python-service/dataocean/chart/`，包含 __init__.py, router.py, service.py, data_aggregator.py, chart_validator.py
- [ ] T002 创建前端图表组件目录 `frontend/src/components/chart/`

## Phase 2: User Story 1 (P1) — 系统自动推荐图表类型

**Goal**: 时间序列数据自动推荐折线图，分类对比数据自动推荐柱状图
**Independent Test**: 不同数据特征返回不同的推荐图表类型

- [ ] T003 [US1] 创建 Python 图表生成服务 `python-service/dataocean/chart/service.py`，实现 generate_chart(query_result, column_types, total_rows) 方法：数据预处理（取前 20 行 + 列类型推断 + 总行数）→ 调用 LLM → 解析返回 ECharts Option JSON
- [ ] T004 [US1] 创建 Python 图表路由 `python-service/dataocean/chart/router.py`，实现 POST /internal/chart/generate 端点，接收查询结果数据和列元信息，返回 ECharts Option JSON
- [ ] T005 [US1] 创建 Python ECharts Option 校验器 `python-service/dataocean/chart/chart_validator.py`，实现 validate_chart_option(option_json) 函数：校验 JSON 合法性、必须包含 series 和 xAxis/yAxis（非饼图）或 series.data（饼图），无效时返回 None
- [ ] T006 [US1] 在 service.py 中实现降级逻辑：LLM 返回无效 JSON 或校验失败时，返回 chart_type=null 和 chart_option=null，前端据此展示纯表格

## Phase 3: 数据聚合逻辑

- [ ] T007 [P] 创建 Python 数据聚合模块 `python-service/dataocean/chart/data_aggregator.py`，实现 aggregate_for_chart(data, column_types, total_rows) 函数：
  - 时间序列 > 50 行：按时间粒度聚合（日→周→月）
  - 分类数据 > 10 类：保留 Top 10 + "其他"
  - 数值数据 > 50 行：分桶统计
- [ ] T008 在 service.py 的 generate_chart 中集成 data_aggregator：total_rows > 50 时先聚合再传给 LLM 生成图表配置

## Phase 4: LangGraph Data_Visualizer_Node 集成

- [ ] T009 创建 LangGraph Data_Visualizer_Node，在 SQL 执行成功后调用 chart service 的 generate_chart 方法，将 chart_option 和 explanation 写入 LangGraph state，失败时 state 中 chart_option=null（不阻塞流程）

## Phase 5: User Story 2 (P2) — 前端 ECharts 渲染和类型切换

**Goal**: 从柱状图切换为折线图后，数据不变，图表正确渲染
**Independent Test**: 图表类型切换不重新请求后端

- [ ] T010 [US2] 创建前端图表 composable `frontend/src/composables/useChart.ts`，封装 ECharts 实例管理：init(dom)、setOption(option)、resize()、dispose()、getDataURL()，窗口 resize 时自动调整尺寸
- [ ] T011 [US2] 创建图表容器组件 `frontend/src/components/chart/ChartContainer.vue`，接收 chartOption prop，使用 useChart 初始化 ECharts 实例，try-catch 包裹 setOption（失败时 emit error 事件触发降级）
- [ ] T012 [US2] 创建图表类型切换组件 `frontend/src/components/chart/ChartTypeSwitcher.vue`，按钮组（bar/line/pie），点击时修改本地 chartOption 的 series[0].type 和相关配置（饼图需要转换 data 格式），emit 新 option
- [ ] T013 [US2] 创建图表导出组件 `frontend/src/components/chart/ChartExporter.vue`，PNG 导出按钮，调用 useChart 的 getDataURL() 生成下载链接

## Phase 6: User Story 3 (P1) — 口径说明和溯源

**Goal**: 用户能看到"本次查询使用了 order_info 表的 pay_amount 字段（可信度 95）"
**Independent Test**: 每次查询结果都附带口径说明

- [ ] T014 [US3] 在 Python 查询响应中构建 explanation 字段：包含 used_tables（表名列表）、used_columns（字段名 + 可信度分数列表）、metric_definitions（指标定义引用）、confidence_warning（低可信字段警告文本）
- [ ] T015 [US3] 创建口径说明组件 `frontend/src/components/chart/QueryExplanation.vue`，展示使用的表（Tag 列表）、字段（表格：字段名 + 可信度 + 来源）、低可信字段标注橙色警告图标和提示文本

## Phase 7: Polish & Cross-Cutting

- [ ] T016 实现单行单列结果判断：查询结果只有 1 行 1 列时不生成图表，直接返回数值展示
- [ ] T017 实现前端图表降级展示：chartOption 为 null 或渲染失败时，隐藏图表区域只展示 ResultTable，不报错不弹窗
- [ ] T018 实现饼图数据超 10 类时的前端聚合：保留 Top 9 + "其他"合并项

## Dependencies

```
T001 → T003-T007
T003 → T004, T005, T006
T005 → T006
T007 → T008 → T009
T003 → T009
T002 → T010-T015
T010 → T011 → T012, T013
T014 → T015
T009 → T014 (explanation 数据来自 Python)
T011 → T017
```

## Implementation Strategy

MVP-first: Phase 2 实现 Python 侧图表生成核心逻辑（LLM 调用 + 校验 + 降级），Phase 3-4 完善数据聚合和 LangGraph 集成，Phase 5 实现前端渲染和交互，Phase 6 补充口径说明。Python 后端和前端可并行开发（Phase 2-4 与 Phase 5 并行），通过 ECharts Option JSON 契约对接。
