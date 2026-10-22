# 009 Schema RAG 构建与检索

> 文档类型：现行模块说明；核对日期：2026-10-20；术语改造相关实现已提交于 `ffb06ce`。真实新 build、无引用旧集合清理与新知识问数沿用 10 月 18 日实测，本轮仅复核文档与提交状态，范围见[验收结论](../acceptance/业务术语与语义知识验收记录.md#验收结论)与[项目现状](../项目现状.md)。

## 概览与责任边界

Java 冻结合规文档版本、来源快照、事实依赖、构建清单与 buildId，管理构建状态、审计、MySQL chunk membership 和活动指针。Python 是切分、Embedding、Milvus 写入/校验、检索与重排的执行方。

**文档发布不会自动建索引。** 不恢复早期“先删活动向量再重建”或发布即向量化方案。

## 构建主流程

```text
合规版本发布 → 有权人员明确确认来源快照的 build
→ Java 冻结版本清单、hash、依赖与 buildId
→ Python 切分、嵌入、写入 buildId 专属 collection
→ 数量与 manifest 校验 → Java 原子切换活动 build
→ 旧 build 等进行中查询引用结束 → 清理与校验
```

失败或被替代的构建不切活动指针，清理失败保留待重试状态。运行中任务固定使用所绑定的原 build，不能切换一半，也不能提前删除原 collection。

## 文本与向量规则

Python `rag/chunker.py`按二级章节、三级语义单元处理 skills.md。目标约 900 tokens、最大 1000、长单元重叠约 150；短而有意义的单元保留，不按固定字符数截断 SQL、Join 或指标。

保留 chunk 类型、顺序与分组、多表/多列、entity、trust、hash 等 metadata。knowledge_chunk 是持久化来源，Milvus 保留检索所需副本。向量字段必须为 embedding 且维度匹配；不兼容集合需要经核实重建，不能静默混写。

## S1 检索边界

检索绑定任务 buildId、数据源专属 collection、来源快照、批准事实 membership、完整依赖与当前 IAM 可见资源；排除 DEPRECATED/BLOCKED。活动 build 的来源快照可以早于最新已发布快照，应显示版本落后提示；旧知识只是候选证据，候选 SQL 仍要通过当前已发布元数据和 S1 授权，不能借旧 build 使用已删除或撤权资源。默认 schema_knowledge 不是 S1 检索源，其历史向量来源问题不通过迁移到新 build 来掩盖。

重排与相邻 chunk 扩展不能跨越上述过滤边界。Embedding 缓存与模型/配置相关；问题兜底按问题、来源快照及当前资源隔离。兜底只在安全知识可用时成立，不返回无来源的成功结果。

## 术语和人工释义来源

`GLOSSARY_TERM`、`SEMANTIC_NOTE` 切为字段说明片段，保留事实类型、来源 ID、审核状态和全部表字段依赖。术语事实追溯到精确审核记录、修订和内容摘要；人工释义事实追溯到已确认候选及其冻结输入。Java 在新构建的版本验证时复核正文与来源，在 S1 授权 membership 时再次验证当前术语/人工确认记录；来源撤回或改变的片段不能继续成为检索证据。缺少有效审核快照的历史术语不自动补成新事实。

工作区新增的 `FIELD_SEMANTIC` 使用 definition/revision/review/binding 四个精确来源 ID、内容 hash、完整依赖 hash 和当前快照绑定。Java 在知识草稿生成/发布时验证事实及正文；Python chunker 将其切为 `FIELD_NOTE` 并保留 `factType` 与来源 IDs。Java 载入 membership 时即时复核当前修订与来源，RAG 候选和相邻扩展仍受该 build 授权 chunk ID 限制。query task 固定本次派发的字段语义引用，并在候选授权、执行开始、执行结果保护和最终落库前检查；引用失效后任务失败并要求重新发起，不切换 build，旧 collection 不提前清理。

禁用/废弃对象通过 `TABLE_EXCLUDED/COLUMN_EXCLUDED` 留在管理覆盖清单，Python 不切为可检索证据。完整覆盖校验和治理门禁同时保留。

本次已补验真实目录/人工释义发布、Embedding、build 激活、无引用旧集合清理及新知识问数；有在途引用的替换没有实测。重排必须保留 build、来源、完整依赖与审核状态，不能在类型转换时丢失后让安全层转入大量备用事实。覆盖范围见[本轮证据](../acceptance/业务术语与语义知识验收记录.md)。

2026-10-22 增加 `FIELD_SEMANTIC` 工作区代码、build membership 当前资格过滤及在途任务引用复核。隔离 H2/定向 Python 测试覆盖资格失效和管道字段保留；本轮没有生成/确认真实 RAG build、跑真实问数或执行 V69，因此这不是 MySQL 或端到端 RAG 验收。

## 代码与验证

| 代码 | 用途 |
| --- | --- |
| [RagIndexBuildServiceImpl](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/RagIndexBuildServiceImpl.java) | 明确确认、manifest、验证、指针与清理 |
| [RagIndexBuildScheduler](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/scheduler/RagIndexBuildScheduler.java) | Java 构建调度 |
| [PythonRagClientImpl](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/client/impl/PythonRagClientImpl.java) | 内部 HTTP |
| [rag](../../python-service/dataocean/rag/) | chunker、embedder、vectorizer、retriever、reranker 与 Milvus |
| [iam_s1/service.py](../../python-service/dataocean/iam_s1/service.py)、[firewall.py](../../python-service/dataocean/iam_s1/firewall.py) | S1 build 与资源边界 |
| [V60](../../backend/DataOcean/src/main/resources/db/migration/V60__snapshot_fact_and_rag_build_lifecycle.sql) | 快照事实与 build 生命周期数据结构 |

知识构建入口由[知识模块](006-knowledge.md)的 rag-builds 提供；Python 内部使用 `/internal/rag`和 `/internal/iam-s1/rag/retrieve`。不能将仍保留的通用向量 API 当作 S1 可跳过构建的路径。

验证 token 边界、完整语义单元、metadata、构建失败/替代/清理、同快照 membership、权限过滤和并发查询引用。已有 [test_rag_build_isolation.py](../../python-service/tests/test_rag_build_isolation.py)及 Java [构建测试](../../backend/DataOcean/src/test/java/com/dataocean/module/knowledge/service/impl/RagIndexBuildServiceImplTest.java)。真实 Milvus 结论必须附当次目标和证据，不能由 mock 或历史数量推断。
