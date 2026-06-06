# Research: skills.md 业务知识库模块

## AI 草稿生成方案

**Decision**: Python FastAPI 端点接收快照数据，使用 Jinja2 模板构造 Prompt，调用 Qwen API 生成 Markdown 草稿

**Rationale**: Jinja2 模板可维护性好，Prompt 结构清晰可调试。Python 端只做生成，不持久化，结果返回 Java 存储。

**Alternatives considered**:
- Java 直接调 Qwen API: 可行但 Prompt 工程和后续 LangChain 集成不便
- LangChain 编排: 过重，草稿生成是单次 LLM 调用，不需要 Agent 编排

## 版本管理方案

**Decision**: 每次编辑保存创建新 version 记录，content 全量存储，不做 diff

**Rationale**: skills.md 内容量有限（通常 5-20KB），全量存储简化实现，版本对比由前端 diff 库处理。

**Alternatives considered**:
- Git-style diff 存储: 实现复杂，skills.md 体量不大不值得
- 仅保留最近 N 个版本: 可能丢失重要历史，不采用

## 乐观锁方案

**Decision**: MyBatis-Plus @Version 注解 + version 字段，更新时自动 WHERE version = ?

**Rationale**: MyBatis-Plus 内置支持，零额外代码。冲突时抛异常，前端提示"内容已被他人修改，请刷新"。

## 发布校验方案

**Decision**: 发布前解析 skills.md 中的表名/字段名引用（正则匹配 `table_name.column_name` 格式），与当前快照对比

**Rationale**: 确保 skills.md 引用的表字段在快照中存在且 governance_status 不为 BLOCKED/DEPRECATED。

**校验规则**:
1. 引用的表必须在 metadata_snapshot 中存在
2. 引用的字段必须 review_status = APPROVED
3. 引用的字段 governance_status 不能为 BLOCKED 或 DEPRECATED
4. 校验失败返回具体问题列表，不允许发布

## 向量化触发方案

**Decision**: 发布时写入 vector_index_task (status=PENDING)，定时任务每 5 分钟扫描并调用 Python 向量化接口

**Rationale**: 异步解耦，发布操作秒级完成，向量化失败不影响发布状态。定时任务支持失败重试（max_retry=3）。

**Alternatives considered**:
- 发布时同步向量化: 阻塞发布操作，用户体验差
- MQ 消息驱动: MVP 阶段引入 MQ 过重
- 事件驱动 (Spring Event): 进程内异步，服务重启丢失任务

## Chunking 策略

**Decision**: skills.md 由 Python RAG 服务切割，先按二级标题 (##) 分章节，再按三级标题 (###) 细切为独立 chunk，每个 chunk 独立向量化

**Rationale**: 二级标题对应大章节，三级标题对应具体表、Join Path、指标、字段防坑和查询场景，细粒度 chunk 更利于精准召回和 chunk_type 重排。

**Chunk metadata**:
- chunk_type: CORE_TABLE / JOIN_PATH / METRIC / FIELD_NOTE / CUSTOM
- related_table: 该 chunk 关联的主表名
- related_column: 该 chunk 关联的字段名（可选）
