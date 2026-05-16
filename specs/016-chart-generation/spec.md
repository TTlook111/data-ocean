# Feature Specification: 图表生成与结果解释模块

**Feature Branch**: `001-full-module-specs`

**Created**: 2026-05-16

**Status**: Draft

**Input**: 系统不仅返回 SQL 查询结果，还需要把结果转化为业务人员能理解的图表和口径说明。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 系统自动推荐图表类型 (Priority: P1)

查询返回数据后，LLM 根据数据特征自动推荐最合适的图表类型并生成 ECharts 配置。

**Why this priority**: 自动图表推荐是系统易用性的关键，让业务用户无需手动选择图表类型。

**Independent Test**: 时间序列数据自动推荐折线图，分类对比数据自动推荐柱状图。

**Acceptance Scenarios**:

1. **Given** 查询结果包含时间维度 + 数值指标, **When** 图表生成, **Then** 推荐折线图
2. **Given** 查询结果包含分类维度 + 数值指标（≤10 类）, **When** 图表生成, **Then** 推荐柱状图
3. **Given** 查询结果包含占比数据, **When** 图表生成, **Then** 推荐饼图

---

### User Story 2 - 用户切换图表类型 (Priority: P2)

用户对推荐的图表类型不满意时，可以一键切换为其他类型。

**Why this priority**: 用户自主选择提升满意度，但自动推荐已能满足大部分场景。

**Independent Test**: 从柱状图切换为折线图后，数据不变，图表正确渲染。

**Acceptance Scenarios**:

1. **Given** 当前展示柱状图, **When** 用户点击"折线图", **Then** 图表切换为折线图，数据不变
2. **Given** 数据不适合饼图（超过 10 类）, **When** 用户强制切换为饼图, **Then** 前端做合理聚合后展示

---

### User Story 3 - 展示口径说明和溯源 (Priority: P1)

查询结果旁展示本次查询的口径说明：使用了哪些表、字段、指标定义和可信度来源。

**Why this priority**: 口径说明是系统可解释性的核心，让用户知道数据从哪来、是否可信。

**Independent Test**: 用户能看到"本次查询使用了 order_info 表的 pay_amount 字段（可信度 95）"。

**Acceptance Scenarios**:

1. **Given** 查询完成, **When** 展示结果, **Then** 旁边显示使用的表、字段、可信度分数
2. **Given** 查询使用了低可信字段, **When** 展示口径, **Then** 标注"该字段可信度较低，结果仅供参考"

---

### Edge Cases

- LLM 返回的 ECharts Option 无效？（降级为纯表格展示）
- 数据超过 50 行时图表如何处理？（先聚合再生成图表配置）
- 查询结果只有一行一列？（不生成图表，只展示数值）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 根据数据特征自动推荐图表类型
- **FR-002**: 系统 MUST 生成有效的 ECharts Option JSON
- **FR-003**: 系统 MUST 支持柱状图、折线图、饼图、表格四种基本类型
- **FR-004**: 系统 MUST 支持用户手动切换图表类型
- **FR-005**: 系统 MUST 在 ECharts 配置无效时降级为纯表格
- **FR-006**: 系统 MUST 展示口径说明（使用的表、字段、可信度、指标定义）
- **FR-007**: 系统 MUST 支持图表导出为 PNG
- **FR-008**: 系统 MUST 对超过 50 行的数据先聚合再生成图表配置

### Key Entities

- **ChartConfig**: 图表配置，包含图表类型、ECharts Option JSON、数据摘要
- **QueryExplanation**: 口径说明，包含使用的表列表、字段列表、可信度来源、指标定义引用

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 图表类型推荐准确率达到 80%（用户不需要手动切换）
- **SC-002**: ECharts 配置有效率达到 95%
- **SC-003**: 图表渲染时间不超过 1 秒
- **SC-004**: 100% 的查询结果都附带口径说明

## Assumptions

- LLM 只生成 ECharts Option 配置，实际数据绑定由前端完成
- 传给 LLM 的是数据前 20 行 + 列类型 + 总行数，非全量数据
- 图表导出使用 ECharts 内置的 saveAsImage 功能
