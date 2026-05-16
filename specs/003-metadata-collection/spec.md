# Feature Specification: 元数据采集模块

**Feature Branch**: `001-full-module-specs`

**Created**: 2026-05-16

**Status**: Draft

**Input**: 元数据采集是整个系统的基础，AI 不能直接凭用户问题猜数据库，而要先依赖系统采集到的元数据。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 管理员触发全量元数据同步 (Priority: P1)

管理员选择一个数据源，点击"全量同步"，系统读取该库的所有表、字段、索引、主外键、注释等元信息，生成元数据快照。

**Why this priority**: 元数据采集是治理、skills.md 生成和 RAG 向量化的前置条件，没有采集就没有后续一切。

**Independent Test**: 同步完成后，系统中能查看到该数据源的所有表和字段信息，且与实际数据库一致。

**Acceptance Scenarios**:

1. **Given** 数据源已配置且连通, **When** 管理员点击"全量同步", **Then** 系统读取 information_schema 并存储所有表、字段、索引、主外键信息
2. **Given** 同步正在进行中, **When** 管理员查看任务状态, **Then** 显示同步进度（已处理表数/总表数）
3. **Given** 同步完成, **When** 管理员查看结果, **Then** 显示本次同步的表数量、字段数量、耗时，并生成 metadata_snapshot

---

### User Story 2 - 系统定时自动同步 (Priority: P2)

系统每天凌晨自动执行元数据同步，检测 Schema 变更并生成新快照。

**Why this priority**: 自动同步保证元数据时效性，但手动同步已能满足 MVP 基本需求。

**Independent Test**: 定时任务触发后，新快照与数据库当前状态一致。

**Acceptance Scenarios**:

1. **Given** 定时任务配置为每天凌晨 2 点, **When** 到达执行时间, **Then** 系统自动执行全量同步并生成新快照
2. **Given** 自动同步失败, **When** 管理员查看任务列表, **Then** 显示失败原因和重试按钮

---

### User Story 3 - 查看快照差异 (Priority: P2)

管理员对比两次快照的差异，了解数据库结构发生了哪些变更。

**Why this priority**: 差异对比是变更感知和告警的基础。

**Independent Test**: 在数据库中新增一张表后重新同步，差异报告中能看到该新增表。

**Acceptance Scenarios**:

1. **Given** 存在两个快照, **When** 管理员选择对比, **Then** 显示新增表、删除表、新增字段、删除字段、类型变更、注释变更的列表
2. **Given** 差异中包含删除表, **When** 该表在 skills.md 中被引用, **Then** 系统标记为高风险变更并告警

---

### Edge Cases

- 数据库中有上千张表时同步性能如何保证？（分批读取，单次 100 张表）
- 同步过程中数据库连接断开如何处理？（记录断点，支持重试续传）
- 系统表、测试表如何过滤？（通过表名前缀规则或手动排除列表）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 支持手动触发全量元数据同步
- **FR-002**: 系统 MUST 支持定时自动同步（可配置频率）
- **FR-003**: 系统 MUST 采集表名、表注释、表类型
- **FR-004**: 系统 MUST 采集字段名、字段类型、字段注释、是否可空、默认值
- **FR-005**: 系统 MUST 采集主键、外键、索引信息
- **FR-006**: 系统 MUST 采集表数据量级（行数估算）
- **FR-007**: 系统 MUST 采集字段基础统计（空值率、枚举值 TopN、最大最小值）
- **FR-008**: 系统 MUST 每次同步生成 metadata_snapshot（含快照编号、Schema Hash、统计摘要）
- **FR-009**: 系统 MUST 支持新旧快照差异对比
- **FR-010**: 系统 MUST 记录同步任务状态和失败原因

### Key Entities

- **metadata_snapshot**: 元数据快照，包含 id、datasource_id、snapshot_no、schema_hash、table_count、column_count、sync_status、quality_score、publish_status（DRAFT/CHECKING/ISSUE_FOUND/APPROVED/PUBLISHED/EXPIRED）、created_at、approved_by
- **db_table_meta**: 表元数据，包含 id、datasource_id、snapshot_id、table_name、table_comment、table_type、row_count_estimate、governance_status、review_status、enabled
- **db_column_meta**: 字段元数据，包含 id、table_meta_id、snapshot_id、column_name、data_type、column_comment、business_meaning、is_primary_key、is_foreign_key、nullable、null_rate、distinct_count_estimate、governance_status、review_status
- **table_relation**: 表关联关系，包含 id、datasource_id、source_table、source_column、target_table、target_column、relation_type、confidence、source
- **schema_sync_task**: 同步任务，包含 id、datasource_id、sync_type、status、started_at、finished_at、error_message
- **schema_change_event**: Schema 变更事件，包含 id、datasource_id、object_type、object_name、change_type、old_value、new_value、risk_level

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100 张表规模的数据库全量同步在 2 分钟内完成
- **SC-002**: 采集的元数据与数据库实际结构 100% 一致
- **SC-003**: 快照差异对比能准确识别所有结构变更
- **SC-004**: 同步失败时管理员能在 1 分钟内定位失败原因

## Assumptions

- 通过 JDBC DatabaseMetaData API 和 information_schema 读取元数据
- 字段统计信息通过采样查询获取（LIMIT 1000 行采样），不做全表扫描
- MVP 阶段包含历史 SQL 解析（自动发现 Join Path 和高频字段），BI 报表导入放后续阶段

## Clarifications

### Session 2026-05-16

- Q: 历史 SQL 解析和 BI 报表导入是否纳入 MVP？ → A: 历史 SQL 解析纳入，BI 报表导入放后续
- Q: 数据库表结构？ → A: metadata_snapshot、db_table_meta、db_column_meta、table_relation、schema_sync_task、schema_change_event（文档第 23.3 节）
