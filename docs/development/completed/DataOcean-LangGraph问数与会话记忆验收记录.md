# DataOcean LangGraph 问数与会话记忆验收记录

> 实施分支：`codex/langgraph-query-memory`
> 方案来源：`docs/development/DataOcean-LangGraph问数与会话记忆恢复方案.md`
> 记录创建：2026-09-26；最终验收更新：2026-09-27。所有端到端数据均为隔离合成测试数据。

## G0：S1 基线与 D 阶段预算冻结

### 隔离环境

- MySQL：专用容器 `dataocean-langgraph-acceptance-mysql`，仅监听 `127.0.0.1:13316`；数据库 `langgraph_fixture`。
- MySQL 问数凭据：`langgraph_fixture_reader`，只有 `products` 和 `sales_orders` 的 `SELECT` 权限；不存在对应用或共享数据库的连接配置。
- 数据源编号 701、用户编号 9001、权限修订 1、元数据快照 8801。IAM-SIMPLE-1 权限快照只允许 `sales_orders` 与 `products`；`customers`、`employee_pay` 为明确拒绝资源。
- 只给当前 S1 回退路径两条本地已审核知识 chunk；为避免触碰共享 Milvus，本轮基线不读取或写入任何既有向量集合。
- Redis checkpointer 的 G0 预检使用当时的专用 Redis 8.10.2；`langgraph-checkpoint-redis` 0.3.9 的 `AsyncRedisSaver.asetup()` 成功，并在关闭后重开 saver 读取到同一 thread 的 checkpoint。当时通用 Redis 为 7.4.11 且没有 RedisJSON/RediSearch，因此该次验收使用专用容器。2026-09-27 通用容器已升级，见 E 阶段后续环境记录；不要把两次环境混作同一次验收。
- 固定题集：6 道可答题（地区销售额、月度订单数、商品类别销售额、季度销售额、订单数、月销售额）和 2 道拒答题（无权客户联系方式、缺少广告归因事实）。结果和题目保存在 `scripts/langgraph-acceptance/fixtures/`。

启动并初始化：

```powershell
docker run --detach --name dataocean-langgraph-acceptance-mysql `
  --label dataocean.test-scope=langgraph-acceptance `
  --publish 127.0.0.1:13316:3306 `
  --volume dataocean-langgraph-acceptance-mysql:/var/lib/mysql `
  --env MYSQL_ROOT_PASSWORD=langgraph-test-only-20260926 `
  --env MYSQL_DATABASE=langgraph_fixture mysql:8.0
scripts/langgraph-acceptance/seed_fixture.ps1
```

只在有权使用 DashScope 测试凭据的本地开发环境运行固定题集：

```powershell
$env:LANGGRAPH_ACCEPTANCE_MYSQL_PASSWORD = 'langgraph-fixture-reader-20260926'
$env:LANGGRAPH_ACCEPTANCE_LLM_ENABLED = '1'
Set-Location python-service
.\.venv313\Scripts\python.exe ..\scripts\langgraph-acceptance\baseline.py
```

运行器强制检查 loopback 地址、端口 `13316`、库名 `langgraph_fixture` 和专用只读账号；未满足时拒绝连接。它不连接仓库共享 MySQL、共享 Redis 或现有 Milvus。

### 初测（2026-09-26，LangGraph 实施前；原题集有一项期望值错误）

| 指标 | 结果 |
| --- | ---: |
| 模型与路径 | DashScope `qwen-flash`；当前 IAM-SIMPLE-1 单次生成主链 |
| 可答题正确（按当时固定期望值） | 4 / 6 |
| 拒答题正确拒绝 | 2 / 2 |
| 发现越权 SQL / 表字段 | 0 |
| 模型调用 | 8 题合计 10 次（包含图表生成） |
| Provider token usage | 输入 7,695；输出 1,064 |
| 该题集估算费用 | ¥0.00275 |
| 单题时延 | p50 2,023 ms；p95 3,758 ms |

单次运行明细由 `output/langgraph-g0-baseline.json` 生成，调用凭据不会写入报告。费用以 DashScope 用量 token 统计按检查日官方单价估算；按量账单为准。价格页：<https://help.aliyun.com/zh/model-studio/model-pricing>。

### D 阶段冻结的运行门槛

此冻结发生在 LangGraph 闭环代码开始前；运行器与固定题集后续不可为迎合新主链改题或放宽目标。

| 限制/目标 | 冻结值 |
| --- | ---: |
| 每问候选 SQL 上限 | 3 次（首次生成 + 最多 2 次修正） |
| 每问模型调用上限 | 8 次（含改写、核对、SQL、结果/图表生成等所有 LLM 调用） |
| 每问总时限 | 90 秒 |
| 每问 AI 估算费用硬上限 | ¥0.10；按当日实际模型和 Embedding 价格、Provider token 用量计费估算 |
| 固定可答题正确率目标 | 6 / 6 |
| 固定拒答题正确拒绝目标 | 2 / 2 |
| 越权执行与无权知识入模 | 0 容忍 |

初次测量后发现固定题集第一题的 `expectedRows` 把 South 写成了 90.00；fixture 中 2026-02 的 South 订单为 90.00，2025-12 的已完成 South 订单为 30.00，因此问题“统计全部已完成订单销售额”应为 120.00。初测的 4/6、2/2 按旧 expectedRows 计算，仅保留为历史记录；已修正 fixture 期望行并使用同一条、未改写的 pre-LangGraph S1 基线链重新测量，冻结门槛没有改变。

### 修正 fixture 后的 pre-LangGraph S1 基线复测

| 指标 | 结果 |
| --- | ---: |
| 可答题正确 | 6 / 6 |
| 拒答题正确拒绝 | 1 / 2 |
| 发现越权 SQL / 表字段 | 0 |
| 模型调用 | 11 |
| Provider token usage | 输入 9,921；输出 1,266 |
| 该题集估算费用 | ¥0.003387 |
| 单题时延 | p50 1,755 ms；p95 7,041 ms |
| 失败拒答题 | `g0-08-missing-campaign-fact` 被错误回答成最高销售地区；没有引用 campaign 字段，但未能拒答缺少的归因事实 |

复测细项由同一 `baseline.py` 生成至本机临时目录；调用本地专用只读 MySQL 和两条固定已审核 fixture chunk，不访问 Milvus。该复测测的是 LangGraph 闭环前的 S1 服务函数，不用于修改 D 的预算目标。

修正后的题集依据：地区 East 40.00 / North 50.00 / South 120.00；Q1 月订单数 2026-01 为 2、2026-02 为 1、2026-03 为 1；类别 Accessories 30.00 / Furniture 180.00；Q1 销售额 180.00；已完成订单 5 笔；2026-02 销售额 90.00。`g0-07` 与 `g0-08` 期望澄清且不得执行 SQL。

G0 自动化结果：Python 原有测试 109 passed；新增 fixture guardrails 4 passed。Redis checkpoint 重启读回 1 passed。在线基线只是一次可复核测量，不是生产 SLA。

## A：知识与 RAG

实现与隔离验证已完成，并已在专用 Java/Python/MySQL/Milvus 链路真实构建：

- 技能文档由 Java 针对一个明确 `snapshotId` 读取完整表、字段、治理状态、关系及已确认血缘，Python 用确定性模板生成；返回覆盖 ID 集合必须和快照完全一致，释义缺失标“待确认”。事实标识、审核状态、来源 ID、完整资源依赖随 chunk 保存。已确认血缘在新快照发布时按 FQN 重新绑定；Join 与血缘分开展示，自动推断关系默认待审核，人工 Join 必须显式确认。
- 发布知识文档只保留 MySQL 文档/版本/审核历史，不自动建索引。用户确认后生成独立 `buildId` 和 Milvus collection；按冻结的已审核文档版本重新切分，校验来源快照/事实清单，成功验证 Milvus 数量后才原子切换 active 指针。失败不影响旧 build。旧 build 没有运行中查询引用后，删除专属 collection 并验证 collection 不存在/向量为零。MySQL fallback 与正常检索都使用当前 active build 的事实成员及完整依赖过滤。
- 查询固定 active build 对应的 embedding provider/model/base URL/dimension；缓存键包含配置身份。Embeddings 从 A 改 B 再改回 A 时，未生效的 B 变更不会成为索引选择条件。RAG 来源快照落后只提示；数据源 readiness 的阻断条件只看当前快照、治理、连通性和 S1 授权。
- 新增 V60，永不占用 V53。隔离 `dataocean_app` 测试库从空库启动，Flyway 完整应用到 V63；不连接共享业务库。
- 定向 Java 验证：知识快照事实校验、发布权限/所有权、RAG 构建服务、关系可见性/跨快照重绑定、人工 Join 权限、readiness、版本回滚及 S1 查询，共 13 个测试类通过；Java `test-compile` 通过。Python 全套 125 passed（含隔离 Redis 8 checkpoint 读回与 RAG 访问控制/构建删除测试）；前端 `npm run build` 通过。
- 实际流程：针对快照 1 生成知识目录文档 1，独立测试账号 `9002` 审核并发布；13 个切片预览覆盖快照全部 10 个字段。独立 RAG build `25e0fc01-f032-4614-9d9a-8adec28f1263` 由该有权账号明确确认，来源快照 1，验证 3/3 向量后成为 `ACTIVE`。切换前第一次写向量因 Java payload 漏传 `sourceSnapshotId` 被 Python 422 拒绝；修复并加回归测试后，新 build 成功。失败 build 专属 collection 已从专用 Milvus 消失。专用 Milvus 的默认 `schema_knowledge` 集合仍有 241 条既有向量，没有被本流程读取、覆盖或删除；问数证据绑定到上述活动 build 专属 collection。
- 自动索引入口已停止为普通文档发布及回滚触发；知识文档审核、发布和 RAG build 均通过独立测试账号及显式操作完成。未读取、覆盖或清理共享 Milvus 集合。

## B：会话与记忆

实现、专用 Redis 8 checkpointer 和 Java/Python 任务交接均已验证：

- 每个会话固定绑定创建时的数据源。问数前以数据库条件更新占用 `active_turn_task_id`，只有该 taskId 能释放；失败、完成、取消和僵尸任务超时均写入助手终态消息并释放轮次。删除会话立即进入 `DELETED`、取消活动任务，所有会话消息及关联任务结果 API 都按当前会话状态拒绝访问。
- 历史消息 API 用稳定 `conversation_message.id` 游标读取，单页最多 100 条并按正序返回；前端默认恢复 50 条，用户可持续加载更早消息。逐条复查历史助手结果，权限撤销、证据缺失或结果不可重护时只显示占位，不中断同页其余消息。
- 摘要覆盖的完整轮次与最近 5 个已完成轮次分开维护，当前提问按消息 ID 排除；仅同一 conversationId 的消息进入上下文。旧助手结果及摘要更新时通过 Java 当前 S1 读取/保护路径复核；摘要记录 `coveredMessageId` 和授权修订，授权修订改变后旧摘要不进入模型。摘要落后时触发重建并暂拒绝新问数，明确提示重试，避免静默丢掉已滑出窗口的轮次。
- MySQL 原始消息仍为完整历史来源；消息分页和会话列表在最后消息 365 天窗口内可见。每天清理已过期且无活动轮次的会话、消息、摘要和关联任务。现有 30 天任务清理显式排除 IAM-SIMPLE-1 任务，避免提前删除 365 天会话需要的授权与结果证据。
- 新增 V61（V53 继续保留为空号）。隔离应用库从 V60 启动后真实执行到 V61，成功标记为 1；实查 `active_turn_task_id` 与摘要 `permission_revision` 字段存在。
- Java 定向测试 4 个类通过：会话互斥/删除/ID 游标页、最近五轮与权限修订摘要、单条失效历史结果占位、S1 任务僵尸清理及 30 天任务清理排除规则。前端全量 Vitest 12 files / 73 tests 通过，`npm run build` 通过。
- 使用 dedicated Redis 8 运行 `AsyncRedisSaver.asetup()` 和图恢复测试，重开 saver 后按 conversation thread id 读回受保护图状态；API 实测会话的消息页按 message ID 先取 assistant、再翻页取回对应 user 消息，assistant 元数据中的受保护行和图表配置已恢复。会话摘要失败只保留 MySQL 原文，不把 Redis checkpoint 作为完整历史来源。

## C：自然语言资源规划

服务端候选资源目录与严格模型合同已实现，并通过真实用户 API 验证：

- 增加 Java `candidateCatalog`，严格按当前已发布 `snapshotId` 和 IAM-SIMPLE-1 当前授权调用统一 Resolver，完整列出当前可见表/字段、治理状态、保护等级、允许 usage、对应授权来源及不含原值的结构化行条件引用；目录构建前后核对权限修订。治理阻断、无授权和隐藏字段不进入目录，脱敏字段仅开放投影。该目录只用于规划，D 阶段仍会对 SQL AST 引用重新 Resolver 授权后才能执行。
- 删除原查询资源选择实现中的 20 表和 60 次探测上限。探测超过旧预算的候选继续逐字段由 Resolver 判定，不能从未遍历误判为无权。Python 合同增加来源快照绑定候选目录与稳定会话 thread ID，仍禁止额外字段。
- Python S1 Milvus 的 ANN 前 chunk ID 白名单、事实依赖再验证、邻块限制、混合 chunk 拒绝与 MySQL fallback 过滤已在 A 的自动化覆盖；D 中图规划只收 Java 当前候选目录和 Java 过滤后的活动 build chunks。实际账号 `9002` 的目录含 products 3 列、sales_orders 7 列；客户表字段不在该目录，也未进入最终 SQL。
- Java 定向测试验证 25 个表候选均可返回、61 个字段中只命中第 61 个的授权仍可发现、隐藏字段不会进入完整 Schema Linking 目录；Python S1 安全用例 48 passed。

## D：LangGraph 闭环

- 依据冻结门槛实现 retrieve → schema linking → SQL generation → semantic check → Java authorize → S1 AST validate → read-only execute → Java protect → result verify → finish/clarify。确认 Join Path 的 JOIN 列仅在该关系已审核、表均被 planner 选中且当前 Java 候选显式允许 JOIN 时补入。缺少精确 glossary 过滤值会被语义门以有界修正拦住；只对 Java 保护后的行做结果核对和 ECharts 配置生成。聚合维度缺 `GROUP BY`、`ORDER BY` 输出别名会先由本地 AST 检查/解析；单列执行错误提示只回传去敏后的修正类别，不把 SQLAlchemy 原始错误或绑定值写进图状态。
- 每个 SQL attempt 使用稳定 attemptId 与 SQL SHA-256；Java 持久化授权/执行状态、只写 Java 脱敏行和安全来源摘要。Python 不持久化原始连接凭据、临时行绑定或原始结果。HTTP/SSE 中断后可以基于 Redis 8 checkpoint 和 MySQL 任务快照恢复；无 checkpoint 时由 Java 重建会话/候选上下文；EXECUTING 状态不确定时 fail-closed，不重复执行。
- 每问使用数据库侧预算账本和预留/结算模型调用：SQL 最多 3 次、模型最多 8 次、Embedding 最多 2 次、90 秒、AI 费用估算 ¥0.10。先前专用隔离环境（build `25e0fc01-f032-4614-9d9a-8adec28f1263`）的 8 题账本共 33 项（25 次 LLM + 8 次 RAG embedding），LLM 输入 70,832、输出 19,456 token；Embedding 输入 278 token；估算费用合计 ¥0.008915。该轮单题最多 4 次 LLM、1 次 embedding、¥0.001623、15,029 ms，均未触发冻结上限；这组历史账本不替代下文 2026-09-27 V64 最终复跑账本。计价依据为验收日 Qwen-Flash / text-embedding-v4 公开单价，实际账单以 Provider 为准。
- 固定题集复跑曾发现两个模型输出问题：广告活动问题被替换成已授权日期维度；客户字段澄清把 `customers` 放进了示例文本。当前图在 SQL 规划前校验“哪个 X 的已审核指标”中的 X 是否能映射到当前可见目录/已审核词条，缺失时直接澄清；澄清输出会屏蔽目录外表字段标识。两个新增回归用例已纳入 Python 测试，最终完整题集分别以通用澄清结束且无 SQL、表字段、结果或无权标识。
- 本轮修复：状态终态 `CLARIFICATION_REQUIRED` 长于历史 `query_task.status VARCHAR(20)`；新增 V63 扩到 32 字符。因 V53 永久未用，迁移使用最高已占用 V62 后的 V63。专用 `dataocean_app` Flyway 从 V62 实际升级到 V63。

### G0 初次固定题集全量验收（2026-09-27，先前隔离环境）

使用 `scripts/langgraph-acceptance/run_iam_s1_g0_acceptance.ps1`，直接经 IAM-SIMPLE-1 HTTP API 逐题运行；runner 先核验 MySQL/Redis/Milvus 的专用容器标签与回环端口、数据源 1 的目标库和活动 build，再用测试账号登录。测试问题与期望行均来自修正后的固定 fixture；SQL/数据比较不依赖 LLM 的列别名。

| 项目 | 结果 |
| --- | ---: |
| 可答题正确 | 6 / 6 |
| 无权客户姓名/电话拒答 | 1 / 1 |
| 无广告归因事实澄清 | 1 / 1 |
| 越权/禁表/禁字段或无权标识进入 SQL、结果与澄清 | 0 |
| 最终保护状态 | 6 道可答题均为 `FINAL_PROTECTED` |
| 验收脚本 | exit code 0 |
| 图表 | 地区、月度、类别汇总返回受保护 ECharts 配置；单值结果使用表格回退 |
| 活动 RAG build / 来源快照 | `25e0fc01-f032-4614-9d9a-8adec28f1263` / snapshot 1 |

按 fixture 期望值归一化比较的实际行：

| 用例 | 受保护结果 |
| --- | --- |
| 地区销售额 | East 40.00；North 50.00；South 120.00 |
| Q1 月订单数 | 2026-01: 2；2026-02: 1；2026-03: 1 |
| 商品类别销售额 | Accessories 30.00；Furniture 180.00 |
| Q1 销售额 | 180.00 |
| 已完成订单数 | 5 |
| 2026-02 销售额 | 90.00 |
| 无权客户字段 | `CLARIFICATION_REQUIRED`，无 SQL/表字段/数据 |
| 缺失广告归因事实 | `CLARIFICATION_REQUIRED`，无 SQL/表字段/数据 |

该真实链路证明了 Java→Python→活动 RAG→S1 AST→MySQL 只读执行→Java 最终保护→会话落库/API 历史恢复和图表结果的服务间路径。前端 API client 已切换为只发自然语言且不提交 tables。E 阶段桌面、390 CSS px 窄屏和历史结果 NORMAL→MASKED 真实负向验收已在本机隔离环境通过；最终审查修复后的固定 G0 八题复跑见下节。

### G0 最终审查修复后的完整复跑（2026-09-27，V64 本机隔离环境）

- 复用 V64 app schema `dataocean_e_acceptance_20260927`、只读合成库 `langgraph_fixture_e_20260927`、IAM-SIMPLE-1 测试账号 userId 9002 / datasourceId 1、snapshot 1 和活动 build `1473e4ce-96fe-4483-82ef-2c64664bb0ab`。runner 通过真实 `/api/iam-s1/query/ask`、任务 GET 与会话消息 API 提问；数据库只读预检确认 fixture 账号 `g0_reader_e27` 仅能 SELECT `products` 和 `sales_orders`。八道问题与期望值直接读取固定 `g0_questions.json`，冻结预算没有改动。
- 最终结果：可答题 **6/6** 且均为 `COMPLETED` / `FINAL_PROTECTED`，正确拒答/澄清 **2/2**，越权 SQL/数据及无权标识泄漏 **0**。每问最多 SQL 1/3、LLM 4/8、Embedding 1/2；客户端耗时最多 9,728ms、服务端耗时最多 9,259ms、估算费用最多 ¥0.002272，均低于 90 秒与 ¥0.10 冻结上限。完整任务 ID、状态、受保护结果、助手历史 metadata、SQL attempt、模型调用/Token/费用账本和每问失败明细均在[`最终 G0 报告`](../../../output/playwright/langgraph-query-memory-final-g0-20260927/g0-final.json)；该次通过报告 failures 为空，先前未达门槛轮次保留为 `g0-attempt-1.json` 至 `g0-attempt-5.json`。
- 初轮暴露 V64 合成 metadata entity scope 缺陷：snapshot 1 的若干 `metadata_entity` 实体未保存 `datasource_id`，导致正常 glossary 字段关联被负责源校验拒绝；我只在 V64 隔离 app schema 内将 10 个 synthetic 列实体范围元数据对齐到已有 `db_column_meta` snapshot 1，并通过 API 把 fixture 中已审核的收入公式、月度订单数、商品类别 Join 和输出别名登记到测试 glossary。交易 fixture 行未改，活动 build 和向量内容未改，迁移仍为 V64。变更证据见[`实体范围修复`](../../../output/playwright/langgraph-query-memory-final-g0-20260927/metadata-entity-scope-repair.json)、[`审核术语映射`](../../../output/playwright/langgraph-query-memory-final-g0-20260927/approved-glossary-mappings.json)、[`审核输出别名`](../../../output/playwright/langgraph-query-memory-final-g0-20260927/approved-glossary-output-aliases.json)、[`复合地区销售额映射`](../../../output/playwright/langgraph-query-memory-final-g0-20260927/approved-glossary-region-revenue.json)、[`字段规则恢复`](../../../output/playwright/langgraph-query-memory-final-g0-20260927/fixture-baseline-restoration.json)及[`应用进程生命周期`](../../../output/playwright/langgraph-query-memory-final-g0-20260927/application-lifecycle-evidence.json)。未达门槛的逐轮任务和失败明细保留在同目录 `g0-attempt-1.json` 至 `g0-attempt-5.json`；最终整套复跑在既定上限内通过，仍应把模型输出波动视作本机验收的复现限制。
- 前置 E 负向复读留下的 `sales_orders.region` MASKED/NAME 规则在最终 G0 前通过 IAM-SIMPLE-1 字段保护 API 软撤销，保留审计历史并恢复 fixture 默认 NORMAL；否则地区期望行会被脱敏。runner 仅在 127.0.0.1 启动 Python/Java，结束后两服务均停止；没有创建/重建容器，没有改 V59 `dataocean`，没有读取或修改 Milvus 默认 `schema_knowledge`。Redis DB 0 继续保留且未清空。该验收仅代表本机隔离合成环境，不是生产或其他环境证据。

## E：前端与端到端验收

- 查询入口仅显示所选数据源、会话和自然语言输入；删除表/字段预选组件与代码文件。API 在无 `tables` 时只上传 datasourceId/question/conversationId，Java 返回完整、当前授权的 candidate catalog。
- 前端恢复按消息 ID 分页；本地轮询到上限且服务端仍为 `PROCESSING` 时才显示“恢复等待”，服务端 `TIMEOUT` 显示重试入口；恢复请求失败会显示错误。澄清状态给出“补充查询条件”入口，进度节点对应真实 LangGraph 阶段。ECharts 是首选结果视图，图表配置缺失或渲染报错时显示同一份 Java 已保护数据表。
- 本次隔离预检与服务：复用现有 MySQL、Redis 8.10.2、Milvus 容器，没有新建或替换容器/卷；Python `127.0.0.1:18000`、Java `127.0.0.1:18080`、Vite `127.0.0.1:5179` 均健康，`8000/8080` 没有监听。只在新验收 schema `dataocean_e_acceptance_20260927` 将 Flyway 从空库迁移到最新版本 V64（V53 未使用），版本 64 成功；该轮隔离验收进行时，常用开发库 `dataocean` 为 V59；当日晚间另行迁移到 V64。问数只读 fixture schema 为 `langgraph_fixture_e_20260927`，账号 userId 9002 / datasourceId 1，快照 1，IAM-SIMPLE-1 只授予 `sales_orders` 与 `products`；测试凭据仅在忽略文件 `scripts/langgraph-acceptance/.env.local`，未写入日志或本文。Redis checkpoint 使用 `127.0.0.1:6379/0`，DB 0 未清空。
- 验收结束后仅停止本轮启动的 Python、Java、Vite 进程；复核 `5179/18000/18080` 已无监听，`8000/8080` 未启动或触碰，MySQL、Redis 8.10.2、Milvus 基础设施容器仍为 healthy。没有停止或替换任何容器/卷。
- 通过语义知识页面确认构建后，活动 build 为 `1473e4ce-96fe-4483-82ef-2c64664bb0ab`，来源快照 1，状态 `ACTIVE`，13/13 chunk/vector 核验通过；专属 collection 为 `dataocean_rag_ds1_b1473e4ce96fe448382ef2c64664bb0ab`。只读 Milvus 元数据确认该专属集合存在、1024 维、13 行；查询任务固定记录此 buildId。整个验收没有读取 `schema_knowledge` 的向量内容，也没有对该集合执行覆盖、删除或重建。
- Browser 缓存目录为 `26.924.22138`，`scripts/browser-client.mjs` 和 `scripts/browser-service.mjs` 均存在。旧 tab 4 在上一轮应用服务停止后停留于 `ERR_CONNECTION_RESET` 的 Chrome 错误页，Browser URL policy 不允许从 `data:` 错误页导航；按 Browser 技能用现有 Browser runtime 新建 tab 5 后，聚焦、导航和 DOM 读取恢复，原已登录会话与验收页面加载成功。此新标签恢复了同一历史会话的最终任务结果。
- 本次 Browser UI 实际结果：G0-01“按地区统计已完成订单的销售额”返回 East 40.00 / North 50.00 / South 120.00，可信依据列出授权的 `sales_orders` 字段，并显示 ECharts 柱状图；G0-05 返回 5，图表不适用时显示“以下为受保护的数据表”和单行 5；G0-08“哪个广告活动带来的销售额最高？”显示澄清并在 SQL 标签页显示“无 SQL”。历史页从最新 50 条加载到 54 条后，旧澄清消息的“补充查询条件”恢复了原问题；旧失败消息重试后，Java/MySQL 将任务 `1b06d09b-6ee1-4562-8359-7ca67794b1d4` 与受保护的一行结果 5 持久化完成。
- 同一历史重试 task 的最终 UI 现已在新 Browser tab 验证：消息显示“查询已安全完成，共返回 1 行”，结果抽屉显示已保护的订单计数 5。随后用该测试账号现有授权会话（token 仅由前端 Axios 拦截器附加，未读取或输出 token）对同一 task 直接请求 Java 与经 Vite 代理请求，各三次，状态均为 HTTP 200 / appCode 200，task 均为 `COMPLETED`、rowCount 1。Browser 计时：直连 Java 111 / 38 / 37ms；Vite 代理 40 / 41 / 46ms。Tomcat access log 同时记录直连 GET 99.410 / 35.607 / 34.740ms，Vite 转发 GET 34.029 / 35.166 / 41.580ms；Vite middleware 记录代理响应 35 / 37 / 43ms。证据见 [`task-get-timings.json`](../../../output/playwright/langgraph-query-memory-e-acceptance-20260927/task-get-timings.json)、[`task-get-server-logs.json`](../../../output/playwright/langgraph-query-memory-e-acceptance-20260927/task-get-server-logs.json) 与 [`java-access-probe.2026-09-27.log`](../../../output/playwright/langgraph-query-memory-e-acceptance-20260927/java-access-probe.2026-09-27.log)。
- 同一 task 的当前 HTTP GET 没有复现 30 秒阻塞；直连 Java 中位数约 38ms，Vite 代理约 41ms，Java GET 与代理日志吻合，因此没有改 `IamS1QueryServiceImpl.get()`、没有增加 Axios 超时，也没有加 GET 方法级计时代码。为了在 Browser 页面使用现有 Axios 拦截器（令牌值未读取）直连 Java，短暂启用了只允许 `http://127.0.0.1:5179`、仅 GET/OPTIONS 的 CORS 探针；随后 SecurityConfig 临时补丁已从工作区撤销，Java 已用正常安全配置重启。探针未部署。
- 诊断中一次无 CORS 的 Java 直连返回 Browser `ERR_NETWORK`（预检被浏览器拦截，实际 GET 未到 Java）；临时 CORS 探针仅允许 GET/OPTIONS，因此也曾阻止一次诊断页的 POST。两项都是诊断配置边界，不是应用查询失败；CORS 撤销后固定问句经 Vite 重试成功。旧 30 秒超时对应的原 Java 进程没有每请求 access log，不能事后证明其精确阻塞点。可以确认的是旧 Browser tab 在本轮恢复时处于 `ERR_CONNECTION_RESET/REFUSED` 错误页，Browser URL policy 不允许从 data 错误页导航；按技能在同一 In-app Browser 新建标签后，聚焦、导航、页面读取、历史最终结果和当前任务 GET 均恢复。结合直连与代理快速响应，旧超时更符合旧标签/连接状态问题，不能据此断言 Java `get()` 代码存在性能缺陷。
- 固定题集的当前 Browser 复核是抽样而非完整 8 题复跑：成功的地区销售额任务为 4 次 LLM / 1 次 embedding / ¥0.001607 / 14,256ms；订单数为 4 / 1 / ¥0.001293 / 9,075ms；缺少广告事实为 0 / 1 / ¥0.000018；历史失败题重试为 4 / 1 / ¥0.001329 / 9,176ms。每个记录均低于冻结的每问 8 次 LLM、2 次 embedding、90 秒、¥0.10 上限；固定 8 题、期望值、预算与 IAM 边界均未修改。初次查询暴露验收 fixture 元数据实体 FQN 与实际 datasource 名不一致，且完成订单口径未包含 COUNT 公式/`order_id` 关系；只修正了隔离 schema 中的合成 glossary/entity 数据后，地区与订单数固定问法才通过。
- CSS 修正后已在 Browser 的 1280×720 宽桌面与 643×755 窄视口复验：两者 `documentWidth` 分别为 1280 / 628（均不大于 viewport）；643px 下单列侧栏、横向历史条和全宽结果抽屉正常，ECharts 有 canvas 且柱形可见，图表不适用的订单数回退表仍显示 5。截图：[`query-echarts-wide-1280.jpg`](../../../output/playwright/langgraph-query-memory-e-acceptance-20260927/query-echarts-wide-1280.jpg)、[`query-echarts-responsive-643.jpg`](../../../output/playwright/langgraph-query-memory-e-acceptance-20260927/query-echarts-responsive-643.jpg)、[`query-table-fallback-responsive-643.jpg`](../../../output/playwright/langgraph-query-memory-e-acceptance-20260927/query-table-fallback-responsive-643.jpg)、[`history-retry-final-responsive-643.jpg`](../../../output/playwright/langgraph-query-memory-e-acceptance-20260927/history-retry-final-responsive-643.jpg)。前端 Vitest 14 files / 79 tests passed，`npm run build` passed；ECharts bundle >500 kB 提示仍在。
- 前一轮 In-app Browser 的真实 390 CSS px iframe viewport（`innerWidth=390`，文档/主体/问数 workspace `scrollWidth=clientWidth=375`；差值为竖向滚动条，不存在横向溢出）检查了单列侧栏、ECharts、表格回退和历史分页，截图见 [`query-echarts-390px-iframe.jpg`](../../../output/playwright/langgraph-query-memory-e-acceptance-20260927/query-echarts-390px-iframe.jpg)、[`query-table-fallback-390px-iframe.jpg`](../../../output/playwright/langgraph-query-memory-e-acceptance-20260927/query-table-fallback-390px-iframe.jpg) 和 [`history-pagination-clarification-prefill-390px-iframe.jpg`](../../../output/playwright/langgraph-query-memory-e-acceptance-20260927/history-pagination-clarification-prefill-390px-iframe.jpg)。旧截图 [`history-retry-final-390px-iframe.jpg`](../../../output/playwright/langgraph-query-memory-e-acceptance-20260927/history-retry-final-390px-iframe.jpg) 只显示最终消息，证据的 `protectedCountPresent=false`，不证明抽屉中的计数 5。本轮补充真实 390 CSS px 视口打开结果抽屉的 [`history-retry-result-drawer-390px.jpg`](../../../output/playwright/langgraph-query-memory-hardening-20260927/history-retry-result-drawer-390px.jpg)，表中清楚显示 5。
- 最终 Console / 请求核查证据保存于 [`browser-console-final.json`](../../../output/playwright/langgraph-query-memory-e-acceptance-20260927/browser-console-final.json)、[`browser-request-failures.json`](../../../output/playwright/langgraph-query-memory-e-acceptance-20260927/browser-request-failures.json)、[`task-get-timings.json`](../../../output/playwright/langgraph-query-memory-e-acceptance-20260927/task-get-timings.json)、[`task-get-server-logs.json`](../../../output/playwright/langgraph-query-memory-e-acceptance-20260927/task-get-server-logs.json) 与 [`java-access-final.2026-09-27.log`](../../../output/playwright/langgraph-query-memory-e-acceptance-20260927/java-access-final.2026-09-27.log)。最终桌面页 Browser Console 为 0 errors / 0 warnings，临时 Vite HTML 注入的早期页面 `error` 监听器亦未捕获错误；390px 嵌入页的 Browser `dev.logs` 每次新建 iframe 时报告 `MutationObserver.observe` 参数非 Node 的 TypeError，但同一临时注入监听器在外层和问数 iframe 均未捕获该错误，直接打开的问数页也无此记录。来源未能由 Browser 工具确定，作为单独的 Browser 控制/嵌入页 Console 观察记录保留，没有静默过滤。最终任务读取与对话恢复相关 Java access log 均为 HTTP 200；三次历史 30 秒超时、诊断期间的 CORS 失败，以及 Java 恢复正常安全配置期间 Vite 的一次 `ECONNREFUSED` 均已恢复，后续请求为 HTTP 200，原始记录保留在失败证据文件。
- **历史结果保护修复**：`IamS1QueryServiceImpl.get()` 是任务 GET、历史分页/消息、导出和 SSE 终态/进度读取的共同保护入口。重读时按当前授权重新脱敏原先 NORMAL 且仍保有原值的数据；一旦当前或保存时字段曾脱敏，或授权修订/元数据快照变化，就清除无法按血缘证明安全的旧 `chartConfig`、`sqlExplanation` 和 `suggestedQuestions`。保存时已按旧策略脱敏的行不尝试恢复原值；策略改变、HIDDEN 字段、行权限收紧或证据不完整时整条结果 fail-closed。用户无 SQL 查看能力时也不返回说明文本。
- **修复后真实负向验收**：复用 V64 隔离应用库 `dataocean_e_acceptance_20260927`、合成只读 fixture `langgraph_fixture_e_20260927`、账号 userId 9002 / datasourceId 1，以及活动 build `1473e4ce-96fe-4483-82ef-2c64664bb0ab`；本轮应用服务 Python 127.0.0.1:8000、Java 127.0.0.1:8080、Vite 127.0.0.1:5179；未创建容器，未读取或修改 `schema_knowledge`，该轮负向验收时 `dataocean` 常用开发库为 V59，后于同日晚间独立迁移至 V64。先确认既有成功任务 `5decb928-9553-4e6e-99dd-b3275ea3c403` 以 NORMAL 规则生成并展示 North / South / East 图表，再在 IAM 字段保护界面将 `sales_orders.region`（columnMetaId 1003）设为 MASKED/NAME，随后重读同一历史结果。该 MASKED/NAME 规则当时保留在隔离 schema；最终 G0 复跑前通过 API 软撤销以恢复固定题集的 NORMAL 基线，审计历史保留。
- 重读的 Browser `GET /api/iam-s1/query/conversations/7/messages?pageSize=50`、直连 Java 任务 GET `/api/iam-s1/query/tasks/5decb928-9553-4e6e-99dd-b3275ea3c403`、任务历史 GET 和 JSON 导出均为 HTTP 200。所有响应 JSON 中原值 North/South/East 均不存在；三行地区为 N****/S****/E***，其未受该规则保护的金额保留。`chartConfig=null`、`sqlExplanation=null`、`suggestedQuestions=[]`、`maskedFields={"地区维度":"NAME"}`、`finalProtectionStatus=FINAL_MASKED`。Browser 的图表页显示“当前结果不适合绘制图表，以下为受保护的数据表”。证据：[`normal-chart-390px.jpg`](../../../output/playwright/langgraph-query-memory-hardening-20260927/normal-chart-390px.jpg)、[`masked-history-chart-tab-390px.jpg`](../../../output/playwright/langgraph-query-memory-hardening-20260927/masked-history-chart-tab-390px.jpg)、[`conversation-messages-after-mask.json`](../../../output/playwright/langgraph-query-memory-hardening-20260927/conversation-messages-after-mask.json)、[`task-get-after-mask.json`](../../../output/playwright/langgraph-query-memory-hardening-20260927/task-get-after-mask.json)、[`query-history-after-mask.json`](../../../output/playwright/langgraph-query-memory-hardening-20260927/query-history-after-mask.json)、[`export-json-after-mask.json`](../../../output/playwright/langgraph-query-memory-hardening-20260927/export-json-after-mask.json)。
- 同轮额外新提问生成的任务 `1c983eee-6044-46fb-939f-3ec525259f71` fail-closed 为 FAILED，响应没有 SQL、行或图表；负向重读使用的是本机隔离库中同日已成功生成图表的合成任务，并非把该失败请求当作图表生成证据。历史重试 task `1b06d09b-6ee1-4562-8359-7ca67794b1d4` 的新 390px 抽屉截图及消息响应也已保存：[`history-retry-result-drawer-390px.jpg`](../../../output/playwright/langgraph-query-memory-hardening-20260927/history-retry-result-drawer-390px.jpg)、[`history-retry-conversation-response.json`](../../../output/playwright/langgraph-query-memory-hardening-20260927/history-retry-conversation-response.json)。响应与抽屉均显示结果 5。
- 为使 Java 加载最后的 DTO 状态变更，本轮在测试编译后主动重启了 Java；Java 停止期间 Vite 的后台通知轮询 `/api/notifications/unread-count` 有 3 次 ECONNREFUSED，查询 API 不在该失败路径内。Java 恢复后任务 GET、查询历史、会话消息和导出均为 HTTP 200。应用端口及停止范围见 [`application-lifecycle-evidence.txt`](../../../output/playwright/langgraph-query-memory-hardening-20260927/application-lifecycle-evidence.txt)。
- **MutationObserver 调查仍未定因**：原 [`browser-console-final.json`](../../../output/playwright/langgraph-query-memory-e-acceptance-20260927/browser-console-final.json) 中五条 MutationObserver.observe TypeError 的 Browser dev.logs 均没有 URL 或调用栈，早期 page window.error 监听器也未捕获。本轮直接 390 CSS px 页面（无 iframe）重放时 Browser Console 与 CDP Runtime.exceptionThrown 均为 0，未复现。仓库应用源码没有显式 MutationObserver；Element Plus el-table 的 key-render-helper / infinite-scroll 依赖含 observer 调用，Browser Runtime 的 Playwright 注入脚本 _setupGlobalListenersRemovalDetection 也含 MutationObserver.observe(this.document, ...) 。没有栈无法区分依赖与 Browser 注入，继续列为未解决项；后续取证见 [`browser-console-attribution.json`](../../../output/playwright/langgraph-query-memory-hardening-20260927/browser-console-attribution.json)。
- **E 阶段：通过（仅本机隔离验收）。** 桌面/窄屏交互及历史结果 NORMAL→MASKED 负向重读通过；最终审查修复后的 G0 固定八题也已在同一类 V64 本机隔离环境全量通过，详见上节。原 390px iframe 的五条 MutationObserver 错误仍未归因。本结论不代表其他环境或生产验收。

## 全量自动化验证

- Java：修复与回归测试完成后执行干净全量 `mvn clean test`，**633 tests passed，0 failures / 0 errors / 0 skipped**（103 suites，H2 `test` profile）；其中 `IamS1QueryServiceImplTest` 28 项通过，覆盖 NORMAL→MASKED、旧 mask policy 变化/旧掩码不恢复、HIDDEN、行权限收紧、sourceTrace 隐藏字段和未跟踪数据键 fail-closed、会话/历史/导出/SSE 读取。另修正了文档静态测试中过时的 V58 下限断言，改为检查当前 V64/V65；未对 MySQL 执行迁移。
- Python：服务端 LangGraph/权限/RAG 回归的先前记录为 57 passed / 1 skipped；本轮没有改动 Python 服务逻辑，临时诊断字段已撤回。针对本轮 runner/隔离目标校验执行 `test_langgraph_acceptance_fixture.py`，6 passed。
- G0 runner/fixture guardrails：本轮 `test_langgraph_acceptance_fixture.py` **6 passed**；新增当前 E 隔离目标身份、V64、只读权限和冻结预算门禁检查。固定 8 题、期望值与预算没有改动；最终完整 G0 运行的逐题及预算证据见上节。
- 前端：本轮没有改动前端代码；之前的 `npm run test:run -- --reporter=dot` 为 14 files / 79 tests passed，`npm run build` 通过。Vite 的 ECharts bundle >500 kB 提示仍是现有分包体积提示，不影响构建。
- 新增 V64 `conversation_context_summary.permission_scope_fingerprint`。该轮验收只对新的隔离 `dataocean_e_acceptance_20260927` 执行 Flyway 至 V64；常用开发库 `dataocean` 彼时为 V59，后续迁移见文末独立记录。`V53` 仍永久未使用。

## 仍需人工审查/未验证项

- 本次 MySQL 隔离 schema V64 与活动 build 映射已完成并可查询；历史 task GET 的三次超时在恢复后的 Browser 和隔离服务上未复现，Java 直连/Vite 代理各三次均返回 HTTP 200。最终 G0 报告另存独立 task/预算证据。隔离验收当时原 `dataocean` 为 V59；后续本机开发库迁移另见下节。
- 当前 Python 配置连接的本地 Milvus `localhost:19530` 只读元数据显示 `schema_knowledge` 为 241 行；集合描述为空、aliases 为空、properties 只有 `timezone=UTC`，可见字段为 primary `id` 与 1024 维 `embedding`，collection-level metadata 没有 owner/source/build 标记。该来源/归属仍未证实。此次新验收 build 使用单独 collection `dataocean_rag_ds1_b1473e4ce96fe448382ef2c64664bb0ab`，13 行且明确映射到 buildId `1473e4ce-96fe-4483-82ef-2c64664bb0ab` / snapshot 1；没有将默认集合作为活动 build，也没有读取任何向量内容或覆盖、删除、重建 `schema_knowledge`。隔离验收当时原 `dataocean` 库为 V59；该库的后续升级不改变本次合成验收的作用域。
- 代码路径核对：Java 新查询从 `activeBuildForQuery` 选定活动 build 并持久化 `buildId` / 来源快照；collection 名按 `dataocean_rag_ds{datasourceId}_b{buildId 去连字符}` 生成并传给 Python。S1 Python 仅接受与 datasourceId/buildId 严格相符的专属 collection；无活动 build 时只过滤 Java 给出的 fallback chunks，错误的 `schema_knowledge` 指针也不会进入 Milvus 检索。通用 RAG 检索同样拒绝默认或不匹配 collection。授权恢复继续使用任务固定的原 build，直至其可安全清理。
- `schema_knowledge` 仍是通用配置和若干低层 helper 的默认名，但没有发现 IAM-SIMPLE-1 问数检索调用该默认集合的路径；collection source ownership 仍需后续在真正隔离的应用库/容器环境中核验。

## 本机常用开发库的后续迁移（独立于上述隔离验收）

2026-09-27 晚间，按开发阶段保持数据库迁移版本一致的要求，将本机常用 `dataocean` 开发 schema 从 V59 顺序迁移到 V64。迁移前对该 schema 使用 `mysqldump --single-transaction --result-file` 生成逻辑备份；本机备份路径与 SHA-256 记在被 Git 忽略的 `.dataocean/local-environment.md`。Spring Boot 以 `DB_NAME=dataocean` 在临时回环端口 18080 启动，Flyway 日志确认依次成功应用 V60、V61、V62、V63、V64；应用启动后即停止。迁移后只读核对：当前版本 V64、失败历史 0、五张新增 RAG/attempt/model-call 表均存在、`conversation_context_summary.permission_scope_fingerprint` 存在；18080/8080 无监听。没有新建或替换容器，也没有操作 Milvus 默认 `schema_knowledge` 集合。

这次开发库迁移与前述 G0/E 合成隔离验收是两项独立操作：八题正确率、预算和 Browser 结果仍只来自 `dataocean_e_acceptance_20260927` 与 `langgraph_fixture_e_20260927`，不能被改写成常用开发库也已执行同一套题集。其他开发电脑取得代码后应按各自当前 Flyway 版本升级并核对，不需要另设发布环境。

## 验收边界

任何共享/真实业务数据库、共享知识向量集合和生产环境都不属于本次测试目标。遇到不能确认用途的容器或数据，保持只读或避开，并记录为未验证项。该记录只将实际已跑通的步骤标为通过。
