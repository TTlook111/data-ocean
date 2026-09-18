# IAM-SIMPLE-1 B3 安全查询链路合同

状态：B3 代码与自动化测试已完成；保持未提交/未推送，未执行 V55/V56 真实数据库升级、服务/浏览器验收、bootstrap 或 B5。

## 独立入口

Java 用户查询入口：

- `POST /api/iam-s1/query/ask`
- `GET /api/iam-s1/query/tasks/{taskId}`
- `GET /api/iam-s1/query/tasks/{taskId}/sql`
- `GET /api/iam-s1/query/tasks/{taskId}/stream`
- `GET /api/iam-s1/query/tasks/{taskId}/export.csv`

Java 到 Python 的内部入口：

- `POST /internal/iam-s1/query/execute`
- `POST /internal/iam-s1/query/tasks/{taskId}/cancel`
- `POST /internal/iam-s1/sql/validate`
- `POST /internal/iam-s1/sql/execute`
- `POST /internal/iam-s1/rag/retrieve`

所有 S1 Python 模型均为 `extra=forbid`，协议字段固定为 `IAM-SIMPLE-1`，安全字段必填；不接受旧别名、旧平铺权限对象或缺字段默认开放。

## 执行边界

Java 通过 B2 Resolver 生成请求级快照，绑定 `taskId/userId/datasourceId/activeMetadataSnapshotId/permissionRevision`。快照和 `query_task` 只保存资源、来源、能力和保护摘要，不保存记录条件原值。原值只在 Java 到 Python 的一次性 `executionBindings` 中用于参数化 SQL。

Python 的统一 Context Firewall 覆盖 schema、RAG/fallback/few-shot、术语、会话历史/摘要和模型输入；HIDDEN 字段、来源不完整/快照不一致 chunk、样例值和绑定参数不会进入模型。SQL AST 检查所有表、字段、投影、WHERE、JOIN、GROUP、ORDER、HAVING、函数、聚合、CASE、窗口、子查询、CTE、UNION、别名和派生来源；星号必须展开，无法追踪来源即拒绝。

结构化行条件由服务端绑定参数并在每张表实际读取位置注入；LEFT JOIN 右表条件进入 JOIN ON。S1 execute 只接受同一份已校验 SQL、完整快照和绑定参数。Python 返回资源/字段来源及 revision/snapshot 摘要，Java 在落库、任务读取、历史、SQL、CSV、反馈和 SSE 前重新检查当前 S1 权限并执行最终脱敏；无法保护的旧聚合结果要求重新查询。

