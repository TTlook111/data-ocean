# README 视觉资产 / README visual assets

本目录服务于根目录的[中文 README](../../../README.md) 与 [English README](../../../README.en.md)。两版共用四张英文短标签说明图与三张中文界面截图；重要架构和流程同时保留在两版正文中。

## 说明图 / Explanatory illustrations

四张说明图于 2026-11-02 使用内置 `image_gen` 生成，经检查后仅编码为 WebP，没有用手写 SVG 或虚构软件界面替代。原始 PNG 保留在生成环境中。图是经过简化的说明，不是运行证据、完整调用图或生产验收结果。

All four illustrations were generated with the built-in image model, reviewed, and encoded as WebP without altering their content. They explain the system; they are not product screenshots, exhaustive call graphs, or evidence of runtime acceptance.

| 文件 / File | 内容 / Content | 像素尺寸 / Pixels |
| --- | --- | --- |
| [cover.webp](cover.webp) | 治理、检索与受保护问数的概念主视觉 / Conceptual cover | 2149 × 732 |
| [architecture.webp](architecture.webp) | 三服务、应用状态与 AI/查询依赖 / Services and dependencies | 1690 × 931 |
| [agent-workflow.webp](agent-workflow.webp) | 六个核心处理节点 / Six core query nodes | 2132 × 738 |
| [governance-loop.webp](governance-loop.webp) | 五阶段与反馈回到治理审核 / Five stages and attributed feedback | 2079 × 756 |

技术核对依据为 [技术栈与职责](../../技术栈与模块职责.md)、[LangGraph 实现](../../../python-service/dataocean/iam_s1/graph.py)、[知识发布](../../modules/006-knowledge.md)与 [RAG 构建](../../modules/009-schema-rag.md)：

- 架构图：三服务＋六个依赖，共九个节点；两条服务主调用、两条 Java 依赖、四条 Python 依赖。前端仅调用 Java；应用 MySQL 与业务 MySQL、缓存 Redis 与 checkpoint Redis 分开。授权与结果保护等内部回调未展开。
- Agent 图：`retrieve → plan → generate_sql → semantic_check → authorize_execute_protect → verify_result`，六节点、五个顺序连接；调度、修正、澄清和终态不在图中。
- 治理图：五阶段、四个前向连接；反馈从问数回到治理审核。文档发布与显式 RAG 构建是不同动作，原问题仍需核验才结案。
- 封面仅用概念符号表达治理、检索、问数，不声明部署拓扑、性能、自动采集策略或业务正确率。

Reviewed against the linked implementation and maintained docs: nine architecture nodes with eight main dependency links; six query nodes with five forward links; five governance stages with four forward links and feedback returning to review. Authorization/protection callbacks and non-main graph branches are omitted. Publication and explicit index building remain separate.

## 界面截图 / Product captures

以下图片为历史合成预览，仅供追溯，不约束当前设计。现行界面和已验证范围见[全站重设计验收](../../acceptance/2026-11-06-全站重设计与基础整治.md)。These historical previews are not current design requirements; see the linked acceptance report for the implemented renewal.

- 渲染日期：2026-11-02；产品代码：`a180b0eab4380759f63e8fa8ef1701df122fb06f`。
- 正式 Vue 组件在本机 Chromium 中渲染，所有 `/api/` 请求由仓内合成夹具应答。未连接 Java、Python、数据库或模型；未使用公司截图、账号或真实业务资料。
- 双语角标是预览标注。耗时、金额、问题数量、授权与准备状态均为夹具值，不是性能指标或真实授权证明。
- 本轮保留截图像素与合成标记，仅调整 README 中的排列、尺寸与说明。只展示正常布局，实际验收仍从[验收索引](../../acceptance/README.md)进入。

These are actual Vue component captures with repository-based synthetic API fixtures. No live backend, database, model, company screenshot, or business data was used. The bilingual watermark identifies the preview. Displayed values are fixtures, not benchmarks. This revision changes presentation in the README, preserving the captures themselves.

| 文件 / File | 内容 / Content | CSS 视口 / Viewport | 夹具来源 / Fixture source |
| --- | --- | --- | --- |
| [query.webp](query.webp) | 连续对话与查询结果 / Conversation and query results | 1440 × 1080 | [query_conversation_v2.py](../../../scripts/frontend-acceptance/query_conversation_v2.py), `AcceptanceFixture` 默认历史会话 |
| [workbench.webp](workbench.webp) | 来源准备与下一步 / Source readiness | 1440 × 900 | [workbench_governance_v3.py](../../../scripts/frontend-acceptance/workbench_governance_v3.py), `Fixture('normal')` |
| [governance.webp](governance.webp) | 问题分布与来源校验 / Issues and source checks | 1440 × 900 | 同上 / Same as above, `Fixture('normal')` |

截图使用 1.5 倍像素密度，未改动组件布局或业务文案；问数对话滚动到顶部，使问题与回答一起可见。两版继续直接引用仓内 [DataOcean 品牌标识](../../../frontend/src/assets/brand/dataocean-mark.png)，未重新生成 Logo。

Captured at 1.5× pixel density, with application layout and copy unchanged. The query conversation was scrolled to its top. Both READMEs continue to use the original repository logo.
