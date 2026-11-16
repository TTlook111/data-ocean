# README 视觉资产 / README visual assets

本目录服务于根目录的[中文 README](../../../README.md) 与 [English README](../../../README.en.md)。两版共用四张说明图；重要架构和流程同时保留在两版正文中。

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
