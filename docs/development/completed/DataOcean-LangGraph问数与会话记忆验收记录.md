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

G0 自动化结果：Python 原有测试 109 passed；新增 fixture 安全与口径测试另随 G0 批次运行。实际在线基线结果是一次可复核测量，不是生产 SLA。

## A：知识与 RAG

待实现/验收。

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
