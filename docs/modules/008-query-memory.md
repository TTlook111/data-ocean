# 008 S1 问数与会话记忆

> 状态：现行模块说明；整理日期：2026-10-24。本文维护当前行为与契约，迁移/运行/验收结论统一见[项目现状](../项目现状.md)及[证据索引](../acceptance/README.md)。

## 概览与主流程

2026-10-24 增加查询期字段档案和问数反馈闭环。新任务开启 `governance_profiles_enabled`，Java 内部 `/internal/iam-s1/query/tasks/{taskId}/field-profiles` 按当前授权、固定来源和精确语义版本提供档案；Python 资源选定后将其用于 SQL 上下文。候选档案读取不等于 SQL 实际使用，反馈归因另以授权请求中的实际字段（含筛选、连接、分组与排序）核验。

用户通过 `POST/GET /api/iam-s1/query/tasks/{taskId}/feedback` 提交并查看自己已完成任务的反馈。一般评价先待归因；字段原因需当前源审核后写入版本化证据，同一来源与版本的规范化 SQL 样例不重复贡献，旧政策事件保留追溯，修改评价撤销旧贡献。无证据显示不足，不记零分；当前试行分数为 `(正向+1)/(总数+2)*100`，是使用证据参考，不能放行权限或覆盖业务相关性。非字段原因保留为关系、指标、SQL 或展示问题，不批量扣字段分。

确认的当前版本负向证据产生再治理任务，新语义修订复查仅进入待验证，人工按原情景与预期结果核验后才标记原问题已验证；旧版本通过不能关闭新问题。用户可查看处理状态，不返回审核私注。审核、计分和审计在同事务完成，事务/并发与防重复覆盖见[治理闭环 TDD 记录](../acceptance/治理闭环TDD实施记录.md)。

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

字段语义接入后，Java 在派发任务时固定 `FIELD_SEMANTIC` 的来源 ID 列表；候选授权、执行开始、Java 结果保护和最终回调落库前实时复核当前 revision/review/binding/dependency 资格。失效后拒绝该任务并提示重新发起；不从旧 build 切换到新 build，不改变 attemptId 或精确 SQL hash 完成合同。已完成历史结果仍按既有当前 IAM 读取合同展示，不作为重新执行授权。

S1 SQL 处理作用域、别名/CTE/集合运算、投影/过滤/连接字段权限、危险函数和外层 LIMIT。投影星号只能按已授权可投影字段安全展开，无法展开即拒绝；COUNT(*) 不能与未展开 SELECT * 混为一谈。上限、参数与超时规则以严格 S1 路径为准。

## 故障与业务澄清

缺少业务指标、时间范围或审核依据可以要求澄清。欠费、额度、认证、限流、网络/响应超时和格式异常进入服务失败，不转成 CLARIFICATION_REQUIRED，也不让用户补业务条件解决服务故障。

Milvus/检索异常只有在同快照、同 build 且通过当前 IAM 过滤的可用知识兜底时才降级；没有安全兜底时失败。图表失败可展示受保护表格，失败不得伪装成功。前端保留问题与适用重试动作。

## 会话与恢复

Java/MySQL 保存会话、消息和结构化长期摘要。每轮发送稳定 user/datasource/conversation threadId、最近五轮完成对话和经当前权限复查的较早摘要；摘要具有 scope fingerprint 与 memory revision。

Redis 8 只存安全 LangGraph checkpoint，初始化或恢复失败时明确停止，不执行无 checkpoint 路径。查询结果不缓存。摘要在助手消息保存后经专用执行器调用 Python，执行器饱和不得让请求线程运行完整 Agent 或摘要 LLM。

Java 持久化对象位于 `query/entity/po`。历史查询条件 `entity.query.QueryHistoryQuery` 继承公共 `PageQuery`，兼容现有 `page/pageSize` 参数并接受标准 `pageNo`。默认排序与字段白名单见[架构说明](../技术栈与模块职责.md#java-对象目录与分页查询)。当前用户、S1 协议范围过滤和历史结果权限复查保持在原查询链路中。

## 关键接口与代码

| 入口 | 用途 |
| --- | --- |
| [IamS1QueryController](../../backend/DataOcean/src/main/java/com/dataocean/module/query/controller/IamS1QueryController.java) | `/api/iam-s1/query`下 ask、tasks、cancel/resume、history、conversations、SQL、CSV 与反馈 |
| [IamS1QuerySseController](../../backend/DataOcean/src/main/java/com/dataocean/module/query/controller/IamS1QuerySseController.java) | 任务 SSE 读取 |
| [query/service](../../backend/DataOcean/src/main/java/com/dataocean/module/query/service) | 尝试、预算、会话与摘要 |
| [iam_s1/graph.py](../../python-service/dataocean/iam_s1/graph.py)、[router.py](../../python-service/dataocean/iam_s1/router.py) | 内部查询、取消、SQL 和 RAG 入口 |
| [conversation](../../python-service/dataocean/conversation) | 保留 `/internal/query/context-summary`路径的摘要能力 |
| [error_messages.py](../../python-service/dataocean/core/error_messages.py) | 安全的服务故障分类与提示 |
| [QueryDatasourceView](../../frontend/src/views/query/QueryDatasourceView.vue) | 问数工作区及历史恢复 |

## 术语来源有效性

直接传给规划器的已审核术语，必须同时有当前修订的定义/完整映射审核快照并通过内容摘要核对；仅保留旧 `APPROVED` 状态的历史记录不自动成为新问数依据。RAG 的术语/人工释义片段在 Java 生成授权 membership 时复核精确来源，再与当前 S1 可见依赖取交集。来源变化不删除活动 collection，也不改变已绑定任务的 build、权限、AST 或完成合同。

COUNT(*) 的唯一输出别名可以排序，其表级来源与无列来源输出标记仍必须保留；这不放行 COUNT(脱敏字段) 或重复别名。生成提示词使用原字段名、经审核的业务别名或技术别名，不能用模板的通用命名建议覆盖别名来源门禁。上下文预算超限返回安全失败，仍受原上限约束。

## 验证入口

相关用例在 [Python tests](../../python-service/tests)的 `test_iam_s1_graph_*`、`test_iam_s1_security.py`和 `test_query_service_failures.py`；Java 查询测试在 [query 测试目录](../../backend/DataOcean/src/test/java/com/dataocean/module/query)。

固定题、真实保护与预算、取消恢复、当前权限收紧后的历史重读和故障分类应分别核对。历史成绩从[验收索引](../acceptance/README.md)进入，不能由文件存在推定端到端通过。
