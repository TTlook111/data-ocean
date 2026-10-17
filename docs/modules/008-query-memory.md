# 008 S1 问数与会话记忆

> 文档类型：现行模块说明；核对日期：2026-10-17；代码基线：`619bca6`。本轮静态核对，运行验收见[项目现状](../项目现状.md)。

## 概览与主流程

用户只需选择可用数据源并用自然语言提问，不要求预选快照、表、字段或输入 SQL；每个会话固定属于一个源。一次提问内为得到一份可核验结果做有界检索与修正，当前不扩展为多份独立子查询的开放探索。

现行编排在 Python `iam_s1/graph.py`，Java 拥有任务、权限、会话、长期摘要和持久化。旧 `agent/`包与旧 QueryController 链已退休，不能照历史 Agent 改造方案重新接入。

```text
Java 接收问题、绑定用户/源/会话与活动 build
→ S1 图检索、资源规划、SQL 生成与语义核对
→ Java 每次尝试重新授权
→ S1 AST 校验、参数化行条件、只读执行
→ Java 最终保护
→ 结果核对、图表/受保护表格与会话保存
```

## 有界执行与完成合同

每问冻结上限为 SQL 3 次、LLM 8 次、Embedding 2 次、90 秒、估算 AI 费用 ¥0.10；细节由图常量与 Java 预算账本共同约束。取消、恢复和重试共享预算，不能重置计数继续执行。

S1 的 COMPLETED 回调必须包含非空 attemptId 和精确 SQL hash，且匹配同一任务已 PROTECTED 的 QueryAttempt；不能接受无尝试证据的旧分支结果或更弱回退。最终脱敏、来源与权限证据不完整时拒绝交付结果。

S1 SQL 处理作用域、别名/CTE/集合运算、投影/过滤/连接字段权限、危险函数和外层 LIMIT。投影星号只能按已授权可投影字段安全展开，无法展开即拒绝；COUNT(*) 不能与未展开 SELECT * 混为一谈。上限、参数与超时规则以严格 S1 路径为准。

## 故障与业务澄清

缺少业务指标、时间范围或审核依据可以要求澄清。欠费、额度、认证、限流、网络/响应超时和格式异常进入服务失败，不转成 CLARIFICATION_REQUIRED，也不让用户补业务条件解决服务故障。

Milvus/检索异常只有在同快照、同 build 且通过当前 IAM 过滤的可用知识兜底时才降级；没有安全兜底时失败。图表失败可展示受保护表格，失败不得伪装成功。前端保留问题与适用重试动作。

## 会话与恢复

Java/MySQL 保存会话、消息和结构化长期摘要。每轮发送稳定 user/datasource/conversation threadId、最近五轮完成对话和经当前权限复查的较早摘要；摘要具有 scope fingerprint 与 memory revision。

Redis 8 只存安全 LangGraph checkpoint，初始化或恢复失败时明确停止，不执行无 checkpoint 路径。查询结果不缓存。摘要在助手消息保存后经专用执行器调用 Python，执行器饱和不得让请求线程运行完整 Agent 或摘要 LLM。

## 关键接口与代码

| 入口 | 用途 |
| --- | --- |
| [IamS1QueryController](../../backend/DataOcean/src/main/java/com/dataocean/module/query/controller/IamS1QueryController.java) | `/api/iam-s1/query`下 ask、tasks、cancel/resume、history、conversations、SQL、CSV 与反馈 |
| [IamS1QuerySseController](../../backend/DataOcean/src/main/java/com/dataocean/module/query/controller/IamS1QuerySseController.java) | 任务 SSE 读取 |
| [query/service](../../backend/DataOcean/src/main/java/com/dataocean/module/query/service/) | 尝试、预算、会话与摘要 |
| [iam_s1/graph.py](../../python-service/dataocean/iam_s1/graph.py)、[router.py](../../python-service/dataocean/iam_s1/router.py) | 内部查询、取消、SQL 和 RAG 入口 |
| [conversation](../../python-service/dataocean/conversation/) | 保留 `/internal/query/context-summary`路径的摘要能力 |
| [error_messages.py](../../python-service/dataocean/core/error_messages.py) | 安全的服务故障分类与提示 |
| [QueryDatasourceView](../../frontend/src/views/query/QueryDatasourceView.vue) | 问数工作区及历史恢复 |

## 验证入口

相关用例在 [Python tests](../../python-service/tests/)的 `test_iam_s1_graph_*`、`test_iam_s1_security.py`和 `test_query_service_failures.py`；Java 查询测试在 [query 测试目录](../../backend/DataOcean/src/test/java/com/dataocean/module/query/)。

固定题、真实保护与预算、取消恢复、当前权限收紧后的历史重读和故障分类应分别核对。历史成绩从[验收索引](../acceptance/README.md)进入，不能由文件存在推定端到端通过。
