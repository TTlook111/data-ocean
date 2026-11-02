# README 视觉资产 / README visual assets

本目录服务于根目录的[中文 README](../../../README.md) 与 [English README](../../../README.en.md)。两版共用同一组图片，界面语言为中文。

## 来源与范围 / Provenance and scope

- 渲染日期：2026-11-02。
- 产品代码：`develop` 的 `a180b0eab4380759f63e8fa8ef1701df122fb06f`。
- 图片由该版本的正式 Vue 组件在本机 Chromium 中渲染，所有 `/api/` 请求由仓内合成夹具应答。未连接 Java、Python、数据库或模型；未使用公司截图、账号或真实业务资料。
- 图片中的双语角标是预览标注，不属于产品界面。查询耗时、金额、问题数量、授权和准备状态均为夹具演示值，不是性能指标、业务结论或真实授权证明。
- 仅截取正常展示场景，用于介绍布局。这不是功能回归、真实业务联调或生产验收；现行证据仍从[验收索引](../../acceptance/README.md)进入。

These are browser captures of the actual Vue components at the commit above, using repository-based synthetic API fixtures. No live backend, database, model, company screenshot, or business data was used. The bilingual watermark identifies the preview and is not part of the application UI. Displayed timings, amounts, issue counts, grants, and readiness states are fixture values, not benchmarks or verified business facts. These captures illustrate layout only.

| 文件 / File | 内容 / Content | CSS 视口 / Viewport | 夹具来源 / Fixture source |
| --- | --- | --- | --- |
| [query.webp](query.webp) | 问数结果与连续对话 / Query results in a conversation | 1440 × 1080 | [query_conversation_v2.py](../../../scripts/frontend-acceptance/query_conversation_v2.py), `AcceptanceFixture` 默认历史会话 |
| [workbench.webp](workbench.webp) | 来源准备与下一步 / Source readiness and next steps | 1440 × 900 | [workbench_governance_v3.py](../../../scripts/frontend-acceptance/workbench_governance_v3.py), `Fixture('normal')` |
| [governance.webp](governance.webp) | 问题分布与来源校验 / Issue distribution and source checks | 1440 × 900 | 同上 / Same as above, `Fixture('normal')` |
| [overview.svg](overview.svg) | 治理 → 检索 → 问数的概念示意 / Conceptual value chain | 1200 × 220 SVG | 手写 SVG；无脚本、外部资源或软件界面 / Hand-authored SVG, no scripts, external assets, or software UI |

截图使用 1.5 倍像素密度，保存为 WebP；未改动组件布局或业务文案。问数截图将真实对话滚动区域移到顶部，使问题与回答一起可见。根 README 另直接引用仓内 [DataOcean 品牌标识](../../../frontend/src/assets/brand/dataocean-mark.png)，未重新绘制 Logo。

Captured at 1.5× pixel density and encoded as WebP. Component layouts and application copy were not changed. The query conversation was scrolled to the top to show the question and answer together. Both READMEs reuse the existing repository logo directly.
