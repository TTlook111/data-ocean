# Feature Specification: Schema RAG 召回模块

**Feature Branch**: `001-full-module-specs`

**Created**: 2026-05-16

**Status**: Draft

**Input**: RAG 负责从向量库中召回和用户问题最相关的表、字段和业务知识，是 NL2SQL 准确性的关键。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 用户提问时系统精准召回相关表 (Priority: P1)

用户输入自然语言问题，系统从向量库中召回当前数据源下最相关的 5-10 张表及关键字段，作为 SQL 生成的上下文。

**Why this priority**: 召回质量直接决定 SQL 生成质量，"召回错了，生成一定错"。

**Independent Test**: 用户问"上月退款金额"时，召回结果包含 refund_record 表和 actual_refund 字段。

**Acceptance Scenarios**:

1. **Given** 用户选择了数据源并输入问题, **When** RAG 执行召回, **Then** 返回 Top 5-10 张相关表及字段，且只来自当前数据源
2. **Given** 向量库中存在 DEPRECATED 状态的表, **When** RAG 执行召回, **Then** 该表不出现在召回结果中
3. **Given** 用户问题涉及多表关联, **When** RAG 执行召回, **Then** 召回结果包含相关的 Join Path 信息

---

### User Story 2 - 已发布内容向量化入库 (Priority: P1)

skills.md 发布或元数据审核通过后，系统自动将内容向量化并写入 Milvus。

**Why this priority**: 向量化是 RAG 能工作的前提，没有向量数据就无法召回。

**Independent Test**: 发布 skills.md 后，5 分钟内新内容能被召回。

**Acceptance Scenarios**:

1. **Given** skills.md 新版本已发布, **When** 向量化任务执行, **Then** 内容按段落切分并写入 Milvus，携带 datasource_id 等 metadata
2. **Given** 向量化进行中, **When** 用户发起查询, **Then** 使用旧版本向量数据，不中断服务
3. **Given** 向量化失败, **When** 管理员查看任务状态, **Then** 显示失败原因，查询继续使用旧版本

---

### User Story 3 - 数据源隔离与准入过滤 (Priority: P1)

RAG 检索时强制按数据源隔离，且只召回已治理通过的内容。

**Why this priority**: 隔离和准入是数据安全和召回质量的基础保障。

**Independent Test**: 用户选择数据源 A 时，绝不会召回数据源 B 的内容。

**Acceptance Scenarios**:

1. **Given** 用户选择数据源 A, **When** RAG 执行检索, **Then** metadata_filter 强制包含 datasource_id=A
2. **Given** 某表 review_status 为未审核, **When** RAG 执行检索, **Then** 该表不会被召回

---

### Edge Cases

- 向量库不可用时如何降级？（使用 skills.md 中的核心表前 5 张作为兜底）
- 召回结果全部是低相关度时如何处理？（设置最低相似度阈值，低于阈值提示用户换个问法）
- 新数据源刚接入还没有向量数据时？（提示"该数据源尚未完成知识准备"）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 支持将已审核元数据和已发布 skills.md 向量化写入 Milvus
- **FR-002**: 系统 MUST 在每条向量记录中携带 datasource_id、metadata_snapshot_id、knowledge_version_no、governance_status、review_status
- **FR-003**: 系统 MUST 在检索时强制附加 metadata_filter（datasource_id + review_status=APPROVED + governance_status in [NORMAL, RECOMMENDED]）
- **FR-004**: 系统 MUST 支持语义召回（向量检索）
- **FR-005**: 系统 MUST 返回 Top 5-10 张相关表及关键字段
- **FR-006**: 系统 MUST 在数据源禁用或删除时按 datasource_id 批量清除对应向量
- **FR-007**: 系统 MUST 确保新版本向量化完成一致性检查后再切换为生效版本
- **FR-008**: 系统 MUST 在向量库不可用时提供降级方案
- **FR-009**: 系统 MUST 支持管理员手动触发指定数据源的重新向量化

### Key Entities

- **VectorIndexItem**: 向量索引记录，包含向量ID、数据源ID、快照ID、知识版本号、chunk类型、治理状态、审核状态
- **VectorSyncTask**: 向量化任务（复用 skills.md 模块定义）
- **RetrievedSchemaContext**: 召回结果，包含表名、字段列表、相关度分数、来源版本

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 召回准确率达到 80%（Top 5 结果中包含正确表的比例）
- **SC-002**: 单次召回响应时间不超过 2 秒
- **SC-003**: 跨数据源召回污染率为 0%
- **SC-004**: 未治理通过的内容召回率为 0%

## Assumptions

- MVP 阶段仅使用向量语义召回，Hybrid Search（BM25 + Dense）在阶段二引入
- 使用 LlamaIndex 封装 Milvus 向量检索逻辑
- Embedding 模型使用 text-embedding-v4，默认 1024 维（可配置为 1536/2048，但需重建 Collection）
- 向量库使用 Milvus 2.x Standalone，Docker 镜像锁定具体版本
- 分块策略：表级分块（表数量 < 100）或字段组分块（单表字段 > 50），skills.md 按二级标题切分
- 重排序：MVP 先用 Milvus 分数 + 规则加权（表名命中、可信字段命中、废弃字段惩罚），阶段二再引入 qwen3-rerank
- 阶段一验收标准：20 个预设问题中至少 16 个召回人工标注的核心表（文档第 27.2 节）
- vector_index_item 表建立平台库与 Milvus vector_id 的映射关系，支持增量更新和排查

## Clarifications

### Session 2026-05-16

- Q: Embedding 模型和维度？ → A: text-embedding-v4，默认 1024 维，通过 EMBEDDING_DIMENSION 配置（文档第 4.5 节）
- Q: 向量库选型？ → A: Milvus 2.x Standalone，阶段二在同一向量库内增强 Hybrid Search，暂不引入 OpenSearch（文档第 25 节）
- Q: 重排序策略？ → A: MVP 用规则加权，召回不稳定时再引入 qwen3-rerank（文档第 4.5 节）
- Q: 向量记录与平台库的映射？ → A: 通过 vector_index_item 表建立映射，支持增量更新和删除追踪（文档第 23.4 节）
