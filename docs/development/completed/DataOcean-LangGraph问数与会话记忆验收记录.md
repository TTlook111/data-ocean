# DataOcean LangGraph 问数与会话记忆验收记录

> 实施分支：`codex/langgraph-query-memory`
> 方案来源：`docs/development/DataOcean-LangGraph问数与会话记忆恢复方案.md`
> 记录创建：2026-09-26。所有端到端数据均为隔离合成测试数据。

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

### 基线结果（2026-09-26）

| 指标 | 结果 |
| --- | ---: |
| 模型与路径 | DashScope `qwen-flash`；当前 IAM-SIMPLE-1 单次生成主链 |
| 可答题正确 | 4 / 6 |
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

G0 自动化结果：Python 原有测试 109 passed；新增 fixture guardrails 4 passed。Redis checkpoint 重启读回 1 passed。实际在线基线结果是一次可复核测量，不是生产 SLA。

## A：知识与 RAG

实现与隔离验证已完成，真实服务链路继续纳入 E 阶段联调：

- 技能文档由 Java 针对一个明确 `snapshotId` 读取完整表、字段、治理状态、关系及已确认血缘，Python 用确定性模板生成；返回覆盖 ID 集合必须和快照完全一致，释义缺失标“待确认”。事实标识、审核状态、来源 ID、完整资源依赖随 chunk 保存。已确认血缘在新快照发布时按 FQN 重新绑定；Join 与血缘分开展示，自动推断关系默认待审核，人工 Join 必须显式确认。
- 发布知识文档只保留 MySQL 文档/版本/审核历史，不自动建索引。用户确认后生成独立 `buildId` 和 Milvus collection；按冻结的已审核文档版本重新切分，校验来源快照/事实清单，成功验证 Milvus 数量后才原子切换 active 指针。失败不影响旧 build。旧 build 没有运行中查询引用后，删除专属 collection 并验证 collection 不存在/向量为零。MySQL fallback 与正常检索都使用当前 active build 的事实成员及完整依赖过滤。
- 查询固定 active build 对应的 embedding provider/model/base URL/dimension；缓存键包含配置身份。Embeddings 从 A 改 B 再改回 A 时，未生效的 B 变更不会成为索引选择条件。RAG 来源快照落后只提示；数据源 readiness 的阻断条件只看当前快照、治理、连通性和 S1 授权。
- 新增 V60，永不占用 V53。隔离 `dataocean_app` 测试库从空库启动，Flyway 完整应用 59 个迁移并到达 V60；不连接共享业务库。
- 定向 Java 验证：知识快照事实校验、发布权限/所有权、RAG 构建服务、关系可见性/跨快照重绑定、人工 Join 权限、readiness、版本回滚及 S1 查询，共 13 个测试类通过；Java `test-compile` 通过。Python 全套 125 passed（含隔离 Redis 8 checkpoint 读回与 RAG 访问控制/构建删除测试）；前端 `npm run build` 通过。
- 自动索引入口已停止为普通文档发布及回滚触发；产品中的新构建按钮要求有权限用户明确确认。本阶段未读取、覆盖或清理共享 Milvus 集合。

## B：会话与记忆

待实现/验收。

## C：自然语言资源规划

待实现/验收。

## D：LangGraph 闭环

待实现/验收。

## E：前端与端到端验收

待实现/验收。

## 验收边界

任何共享/真实业务数据库、共享知识向量集合和生产环境都不属于本次测试目标。遇到不能确认用途的容器或数据，保持只读或避开，并记录为未验证项。该记录只将实际已跑通的步骤标为通过。
