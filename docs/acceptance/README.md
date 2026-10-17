# 验收记录索引

> 文档类型：验证与历史验收索引；整理日期：2026-10-18。对象目录/分页与术语/知识改造的工作树结果分列于下表；历史记录保留原日期，不推广为当前全量通过。

| 记录 | 时间和覆盖 | 需要保留的限制 |
| --- | --- | --- |
| [业务术语与语义知识](业务术语与语义知识验收记录.md#验收结论) | 2026-10-17～18：修改完成，核心流程验收通过；含迁移、术语审核、AI 采纳发布、新 RAG、保护问数和历史重读 | 隔离单源实测；全量权限、跨源、双用户、大快照及在途替换仍待专项验证 |
| [Java 对象分层与分页验证](../项目现状.md#已有验收证据) | 2026-10-17 工作树：干净编译、Java 全量测试、分页与用户导出回归 | 测试配置下验证；未做真实 MySQL 迁移、API 或浏览器验收；复跑命令见[开发指南](../development/本地开发与验证.md#java-对象分层与分页回归) |
| [LangGraph 问数与会话记忆](LangGraph问数与会话记忆验收记录.md) | 2026-09-27 G0/A/B/C/D/E、预算、保护与历史读取 | 本机隔离合成库；Redis 复用、Browser 归因与默认集合来源限制见原文 |
| [前端设计完整时间线](前端设计验收记录.md) | 2026-10-03～05 问数、后台、统一视觉、后续用户修正与回归 | 各轮环境/页面不同；旧阻塞可能已解决，旧 passed 也不能覆盖后来全部改动 |
| [轨道 A/B 历史执行与状态记录](../archive/plans/DataOcean后台重构状态与整改计划.md) | 原记录截至 2026-10-05，含 9 月轨道验收 | 混合阶段状态，不再是当前状态入口；当前结论见[项目现状](../项目现状.md) |

## 原始机器证据

以下为仓库根目录下的本机证据定位，许多文件未跟踪进 Git，换机器须独立核对可用性，不因此更改历史成绩：

- `output/playwright/轨道A浏览器验收证据.json`：轨道 A 129 条结果。
- `output/playwright/langgraph-query-memory-final-g0-20260927/g0-final-p1-completion-final.json`：P1 完成合同修复后的 G0 八题及预算。
- `output/playwright/langgraph-query-memory-e-acceptance-20260927/`：桌面、390 CSS px、历史读取。
- `output/playwright/admin-product-implementation-20261003/`：任务优先后台与三个参考方向。
- `output/playwright/ocean-glass-final-20261004/`：视觉最终对照与范围。
- `output/playwright/title-help-20261005/verification.md`：右上角标题说明与嵌入页修正。
- `output/playwright/query-service-failure-20261005/real-failure-evidence.json`：服务失败分类、任务与历史重读。
- `output/playwright/query-service-failure-20261005/python-tests.txt`：该轮 Python 输出。

## 后续如何记录

每轮写明日期、代码版本、目标环境/库、真实或合成数据、执行范围、结果、跳过项和剩余限制。当前结论回写项目现状，原始输出继续保存 output/playwright。不复制一份平行状态看板，不把历史环境问题写成今天仍然发生。
