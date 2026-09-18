# IAM-SIMPLE-1 B3 安全查询链路合同

状态：B3 代码与自动化测试已完成，并已提交为 `d9a0c3b`；后续 `B3.1 P1 修复`（usage 强制 / 外层行数上限 / SSE 读取路径）、`B3.2 阻断修复`（作用域解析 / 跨库引用 / 记录条件作用域 / 功能权限复查）、`B3.3 脱敏修复`（集合运算按列位置汇总来源）、`B3.4 保护一致性修复`（同一输出列多策略拒绝 / 分支列数双向校验）与 `B3.5 规范化修复`（列名按小写统一判定脱敏冲突 / 分支输出必须有可追踪来源）已在此基础上实现。分支未推送，未执行 V55/V56 真实数据库升级、服务/浏览器验收、bootstrap 或 B5。

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

## 字段使用位置（usage）

`S1Column.usage` 表示字段被允许出现的位置，取值集合必须与 Java `IamS1ColumnUsage` 完全一致：`PROJECTION/FILTER/JOIN/ORDER/GROUP/HAVING/FUNCTION/SUBQUERY`。Java 枚举能产出的每个取值，Python 合同必须接受，否则整份快照会被 Literal 校验直接拒绝。

B3 在 AST 层强制其中三个位置：

- `PROJECTION`：决定字段值能否被暴露。未声明 PROJECTION 的字段不得出现在投影列表，也不得被 `SELECT *` / `table.*` 展开带出。
- `FILTER`：字段能否出现在 WHERE。
- `JOIN`：字段能否出现在 JOIN ON。

`ORDER/GROUP/HAVING/FUNCTION/SUBQUERY` 在 B3 暂缓细分，不作为拒绝理由；后续如需收紧，须同步更新本节与 `_ENFORCED_USAGE_ROLES`。

服务端注入的记录条件谓词不受请求方 usage 声明约束：其出现位置由连接语义（LEFT JOIN 右表进 ON、保留侧进 WHERE）决定，不由请求方选择，因此注入后的 SQL 复测时关闭 usage 强制（`enforce_usage=False`）。模型产出的 SQL 原文始终开启强制；`/sql/execute` 仍以"原文按 usage 校验 + 原文重注入后与 validatedSql 严格字符串比对"作为同一性证明。

## 行数上限

行数上限只按最外层查询自身的 LIMIT 判定：子查询、派生表或 UNION 分支中的 LIMIT 不能代表外层结果集已被封顶。`limit_rule` 检查所有 LIMIT 节点，任一节点超过 `max_result_rows` 即拒绝。应用层仍按 `max_result_rows` 截断并置 `truncated`。

## SSE 推送

SSE 结果推送必须复用 `GET /tasks/{taskId}` 的同一读取路径：viewSql 能力判定、当前权限复查和最终脱敏在两条通道上完全一致，禁止从持久化记录直接构造推送载荷。读取被拒绝时只推送 `error` 事件（仅含可公开原因），不推送任何结果载荷。

无结果载荷的任务（失败、已取消）必须可被读取并返回真实 `errorMessage`；不得因"无字段集可再保护"而报"权限已变化"。

## 名称作用域

表别名、CTE 名与派生表别名一律在**其所属作用域**内解析，嵌套作用域不得参与外层解析：

- 内外层使用同名别名时，外层引用必须解析到外层来源。禁止用全局别名表，否则内层子查询可以用同名别名把外层引用指向另一张表，出现"校验通过的表"与"实际读取的表"不一致。
- 引用第三方数据库/目录的限定表名（如 `other_database.orders`、`a.b.c`）一律拒绝；S1 查询只允许当前数据源内的未限定表名。
- 派生表与 CTE 的 `别名.列` 通过其投影追踪到物理来源，`used_columns` 记录的是物理表字段；追踪不到（输出列不存在、来源表达式为常量、循环引用）即拒绝。

### 集合运算输出

`UNION` / `UNION ALL` / `INTERSECT` / `EXCEPT` 的结果列名取自**第一分支**，但每个分支都向同一列位置供值。因此外层引用必须按**列位置**在全部分支上汇总来源：

- 外层 `q.x` 必须同时追踪到每个分支该位置的物理字段，`used_columns` 与 `sourceTrace` 都要包含全部来源。若只取"首个按名字命名的分支"，后续分支的脱敏字段不会挂到最终列名上，最终脱敏对该列失效。
- 顶层直接是集合运算时同样要按位置汇总（此时不存在外层 SELECT）。
- 派生表与 CTE 之下的集合运算、以及嵌套集合运算（需展平全部分支）同样适用。
- 各分支列数不一致即拒绝，**两个方向都拦**：后续分支列数更少（原实现已覆盖）或更多都必须拒绝，不得依赖数据库报错。
- **每个分支的该位置都必须能追踪到物理字段来源**。常量、字面量或纯函数输出没有来源，必须在 AST 阶段直接拒绝：否则校验会通过、查询会真的打到数据库，随后才由 Java 的空来源检查拒绝结果，既浪费一次执行也违反"常量或无法追踪来源即拒绝"的约束。
- 第一分支以外的输出别名不构成对外列名：`q.y` 应被拒绝，而不是被当作可用列。
- 脱敏字段按集合运算位置被引用时（例如 union 级 `ORDER BY`）仍受"脱敏字段只能直接投影"约束，不因位置对齐而放宽。

### 同一输出列只能有一种脱敏策略

最终脱敏以**列名小写规范化后**为键（Java `maskResultByFields` 会把脱敏映射的键与数据列名都转小写再匹配，且必须使用 `Locale.ROOT` 以避免默认 locale 的大小写折叠差异），因此同一列名汇入多种非空脱敏策略时，无论取哪一个都会用错误的策略处理另一部分数据。此类查询必须**拒绝**，不得静默取其一：

- 多个分支的同一列位置分别来自 `PHONE` 与 `EMAIL` 等不同策略时拒绝，且与分支顺序无关。
- 两个同名输出列（含派生表别名不同但列名相同）携带不同策略时同样拒绝。
- **大小写不作为区分依据**：`phone AS X` 与 `email AS x` 在 Java 侧会归并到同一个键，Python 侧的冲突判定必须使用同一套小写规范化，否则该查询会绕过冲突检查。
- 相同策略重复汇入同一列是允许的，包括仅大小写不同的同名列。
- 空策略不参与冲突判定，但也不能作为"统一策略"使用。若将来需要允许汇聚，必须先定义一种显式的统一强脱敏策略，而不是在运行时二选一。
- **两侧必须独立实现该约束，不得只依赖上游校验。** Python 在 AST 阶段拒绝；Java 的 `deriveOutputMasks` 也必须按 `outputColumn` 的 `toLowerCase(Locale.ROOT)` 自行聚合策略，发现多个不同非空策略时 fail-closed：任务完成阶段拒绝落库（`REJECTED_FINAL_PROTECTION`），任务与历史读取阶段拒绝返回。Java 是最终保护边界，历史持久化任务、上游协议回归或异常来源都可能带来含冲突的 `sourceTrace`。

## 记录条件注入位置

记录条件只注入到**真正读取该表的作用域**。递归收集嵌套表会把内层表的条件注入到外层，生成外层并不存在的表限定条件，导致合法查询无法执行。放置规则：

- 该表在 JOIN 中且连接已有 `ON`：追加到该 JOIN 的 `ON`。
- 该表是 `RIGHT JOIN` 的保留侧：落到 `WHERE`（`ON` 不会过滤保留侧）。
- 该表是 `RIGHT JOIN` 的 FROM 侧：追加到该 RIGHT JOIN 的 `ON`。
- 连接没有自己的 `ON`（逗号连接、交叉连接）：落到 `WHERE`，不得凭空生成 `ON`。
- `FULL JOIN`：拒绝注入。

## 功能权限复查

`query:use` 属功能权限，必须在**读取时**按当前状态复查，不得只在提交时校验。任务读取是唯一入口，`GET /api/iam-s1/query/tasks/{id}`、`/sql`、`/export`、`/export.csv`、`/history`、`/feedback` 与 SSE 均经由它；撤销 `query:use` 后上述通道一律不得再返回结果，即使用户仍保留数据授权。

