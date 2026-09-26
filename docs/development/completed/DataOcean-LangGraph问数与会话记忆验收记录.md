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
- Redis checkpointer 预检：专用容器 Redis 8.10.2；`langgraph-checkpoint-redis` 0.3.9 的 `AsyncRedisSaver.asetup()` 成功，并在关闭后重开 saver 读取到同一 thread 的 checkpoint。当前共享 Redis 7.4.11 不满足该 saver 要求，验收与开发服务必须指向专用 Redis 8 容器；普通缓存升级尚未操作。
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
- 每问使用数据库侧预算账本和预留/结算模型调用：SQL 最多 3 次、模型最多 8 次、Embedding 最多 2 次、90 秒、AI 费用估算 ¥0.10。最终隔离复跑的 8 题账本共 33 项（25 次 LLM + 8 次 RAG embedding），LLM 输入 70,832、输出 19,456 token；Embedding 输入 278 token；估算费用合计 ¥0.008915。单题最多 4 次 LLM、1 次 embedding、¥0.001623、15,029 ms，均未触发冻结上限。计价依据为验收日 Qwen-Flash / text-embedding-v4 公开单价，实际账单以 Provider 为准。
- 固定题集复跑曾发现两个模型输出问题：广告活动问题被替换成已授权日期维度；客户字段澄清把 `customers` 放进了示例文本。当前图在 SQL 规划前校验“哪个 X 的已审核指标”中的 X 是否能映射到当前可见目录/已审核词条，缺失时直接澄清；澄清输出会屏蔽目录外表字段标识。两个新增回归用例已纳入 Python 测试，最终完整题集分别以通用澄清结束且无 SQL、表字段、结果或无权标识。
- 本轮修复：状态终态 `CLARIFICATION_REQUIRED` 长于历史 `query_task.status VARCHAR(20)`；新增 V63 扩到 32 字符。因 V53 永久未用，迁移使用最高已占用 V62 后的 V63。专用 `dataocean_app` Flyway 从 V62 实际升级到 V63。

### G0 固定题集的真实 LangGraph 最终验收（2026-09-27）

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

该真实链路证明了 Java→Python→活动 RAG→S1 AST→MySQL 只读执行→Java 最终保护→会话落库/API 历史恢复和图表结果的服务间路径。前端 API client 已切换为只发自然语言且不提交 `tables`；但 integrated Browser 的自动控制组件本机缺少其请求的 `browser-service.mjs` 版本，未完成可视化浏览器点击/截图验收。不会把该项写成通过，详见 E 风险记录。

## E：前端与端到端验收

- 查询入口仅显示所选数据源、会话和自然语言输入；删除表/字段预选组件与代码文件。API 在无 `tables` 时只上传 datasourceId/question/conversationId，Java 返回完整、当前授权的 candidate catalog。
- 前端恢复按消息 ID 分页；重连/“继续等待”调用 Java resume endpoint；澄清状态给出“补充查询条件”入口，进度节点对应真实 LangGraph 阶段。ECharts 是首选结果视图，图表配置缺失或渲染报错时显示同一份 Java 已保护数据表。
- 前端静态/组件和 API 端测试：Vitest 12 files / 74 tests passed；`npm run build` passed。完整 G0 API 题集由上述脚本 6/6 + 2/2 通过。
- browser 页面的 DOM/可视布局与交互回归未通过 Browser 工具执行：本机已提供的 Browser 控制入口加载时报告缺少 `browser/26.924.20706/scripts/browser-service.mjs`（可用缓存只有 `26.908.70816`）。没有使用其他浏览器控制器绕过；仍需在 Browser service 修复后补一轮登录、发问、图表/表格和历史分页的可视验收。

## 全量自动化验证

- Java：`mvn test`，623 tests passed，0 failures / 0 errors；Spring integration tests 使用 H2 `test` profile，不访问专用验收 MySQL。`IamS1EndpointCoverageTest` 已覆盖新的 resume endpoint 审计豁免，旧 endpoint handler 仍有精确例外清单。
- Python：`pytest -q`，148 passed，4 个 `langgraph-checkpoint-redis` 上游 `redisvl` deprecation warnings；恢复测试和异步 saver 测试连接专用 Redis 8 的 DB 15，与问数链使用的 DB 0 隔离。
- 前端：`npm run test:run -- --reporter=dot`，12 files / 74 tests passed；`npm run build` 通过。Vite 仍提示 ECharts bundle 大于 500 kB，这是现有分包体积提示，不影响构建。
- Flyway：隔离 `dataocean_app` 从 V62 应用 V63 成功，当前专用库为 V63；V53 仍未使用。

## 仍需人工审查/未验证项

- integrated Browser 自动化环境无法加载工具运行所需的 `browser-service.mjs`，所以 E 阶段的可视 DOM/点击/截图验收没有执行。API 级完整题集、受保护结果、会话分页、持久 ECharts JSON 和前端单元/构建均通过，但不能把实际浏览器交互写成通过。
- 专用 Milvus 仍保留默认 `schema_knowledge` 集合的 241 条既有向量；其来源在本轮未重建，本流程未读取、覆盖或删除它。问数记录绑定的活动 collection 是每个 `buildId` 独立的 collection。检查共享 Milvus、清理默认 collection 或做任何知识库重建均不属于本次授权范围。
- Browser 工具恢复后，需要人工查看 ECharts 图表主题/布局、窄屏结果表格回退、Clarification 输入和重连后页面进度；本轮 API 结果没有代替这些视觉审查。

## 验收边界

任何共享/真实业务数据库、共享知识向量集合和生产环境都不属于本次测试目标。遇到不能确认用途的容器或数据，保持只读或避开，并记录为未验证项。该记录只将实际已跑通的步骤标为通过。
