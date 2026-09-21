# DataOcean IAM-SIMPLE-1 B5 切换与回退手册

> 文档状态：B5 执行手册（只写步骤，本轮不执行）
>
> 编写日期：2026-09-21
>
> 适用分支：`codex/iam-s1-b5-preparation`（HEAD 基于 `1f0a5f4`，已包含 IAM 实现提交 `1a6e426`）
>
> 配套检查：`scripts/iam-s1-b5-preflight.ps1`（仓库只读，不连真实库）；真实库只读 SQL 门禁见第 4.4 节
>
> 本文件不代替 `docs/development/DataOcean后台重构状态与整改计划.md`。当前完成度仍以该状态文档为准。

---

## 0. 本轮边界

本手册只用于切换前准备、迁移演练设计和真实验收。编写本文件、运行只读 preflight 或自动化测试，都不构成 B5 已完成。

允许：只读检查 Git、迁移、配置、代码和机器环境；编写本文与只读脚本；增加不连接真实数据库的静态测试；整理验收矩阵和初始化清单。本准备轮可提交手册、preflight 与静态测试，不构成 B5 已执行。

禁止：本准备轮连接真实数据库（含只读）；真实 B5 开始后在 SQL 门禁失败时继续；执行 Flyway migrate；启动应用；启动、创建、删除或重建 Docker 容器；执行真实 bootstrap；修改真实账号、角色、负责源或数据授权；删除旧权限入口；正式切换。

真实 B5 的只读 SQL 门禁（第 4.4 节）只能在用户提供数据库连接之后执行。未提供连接前不得探测真实库。

---

## 1. 当前基线

以下事实在 2026-09-21 准备轮冻结，后续真实 B5 开始前必须再次核对。

| 项 | 冻结结论 |
| --- | --- |
| 真实数据库 Flyway 版本 | **V50**。`V51`、`V52`、`V54`、`V55`、`V56`、`V57` 均未对真实 MySQL 执行。 |
| 待执行迁移 | 按编号顺序一次执行：V51 → V52 → V54 → V55 → V56 → V57。仓库中不存在 `V53__*.sql`。 |
| `iam_s1_*` 表 | 真实库中不存在。测试 profile 为 `flyway.enabled=false` + H2，自动化全绿不能证明这些表已在真实 MySQL 建出。 |
| 真实 bootstrap | **尚未执行**。`iam_s1_bootstrap_state` 在真实库中不存在，因此也不存在 `COMPLETED` 状态。 |
| B4 验收边界 | 批次 1～6 已完成代码与自动化验证（已迁移 Controller **23** 个；批次 6 实际 **61** 个 Handler）。B4 最新自动化基线为 Java **557**、前端 Vitest **67**。B4 **没有**真实服务启动或浏览器验收。 |
| 新旧权限关系 | **不兼容**。新授权只读 `iam_s1_*` 事实。禁止旧角色、旧授权、旧 JWT claim、旧权限缓存的映射、回填、双读或兼容兜底。 |
| 旧入口保留 | `RoleController` / `PermissionController` / `DatasourcePermissionController` / `AccessPolicyController` / `AccessApprovalController` 以及 `/admin/access/organization` 保留到 **B6** 删除。正式旧组织导航在 **B5 切换时移除**，不是本准备轮删除。 |
| 权限与组织域 22 个 `IamS1*` 端点 | 仍使用显式 `IamS1AdminGuard`，不走通用切面。这是 B4 既定边界，不阻塞 B5 维护窗口，也不等于可以沿用旧权限。 |

B4 自动化验证 ≠ B5 完成。本手册写完后，B5 仍未执行。

---

## 2. V53 决策（已冻结）

仓库中 **没有** `V53__*.sql`，也没有必须在本轮落地的 V53 业务需求。P9 告警历史仍是后续功能，不是 B5 的前置。

**最终选择（二选一已定，不再预留）：**

1. **V53 编号永久不再使用。**
2. **P9 告警历史未来改用 V58 或当时更高的未占用版本**，不得回填 V53。
3. **V54～V57 一旦在真实库执行，禁止再新增 V53。** 生产 Flyway 未开启 `outOfOrder`（默认 `false`），`validateOnMigrate` 默认 `true`。在最大已应用版本高于 53 之后插入 V53，会判为 out-of-order 并导致启动失败。
4. **不为了填编号创建没有业务意义的空 migration。** 空文件不能通过审查，也不能当作“占号”。

因此：B5 维护窗口按 V51、V52、V54、V55、V56、V57 的现有空洞编号执行；空洞本身合法，补洞才非法。

---

## 3. migration 清单与静态审查

审查范围：`backend/DataOcean/src/main/resources/db/migration/` 中的 V51、V52、V54、V55、V56、V57，以及对应 Java Entity/Mapper 字段。本轮 **未** 连接真实数据库，**未** 执行 Flyway。

公共结论：

- 依赖顺序正确：V51/V52 只改已有业务表；V54 建 S1 配置基础；V55 依赖 V54 的协议但不建外键；V56 给 `query_task` 增加可空 S1 证据列；V57 只新增申请/审批表。
- 六份脚本均 **无** `FOREIGN KEY` / `REFERENCES`。
- 六份脚本均 **不** 读取、更新或删除 `sys_role`、`sys_permission`、`sys_user_role`、`sys_role_permission`、`datasource_access`、`datasource_access_policy`、`access_approval_request`。
- 六份脚本均 **不** 删除业务账号、组织、数据源、元数据、知识、会话或审计历史。
- 六份脚本均 **无** 旧授权回填。
- Flyway 对已成功记录的版本不会重跑；“重复执行边界”指失败后人工重试或误跑同一 DDL 的行为。

### 3.1 逐迁移审查表

| 版本 | 作用 | 依赖顺序 | MySQL 语法 | 表/列/索引冲突 | 默认值与 NOT NULL | 重复执行边界 | 无外键 | 不改旧权限事实 | 不删业务数据 | 无旧授权回填 | Entity/Mapper 一致性 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| V51 | 回填 `knowledge_doc_version.review_status` / `reviewer_id`，并修正列注释 | 依赖 V13 已存在的 `review_status`、`reviewer_id` 和 `knowledge_review_task`。与 S1 表无关，可先于 V54 执行。 | `UPDATE` 相关子查询 + `LIMIT 1` 为 MySQL 合法写法；更新目标与子查询表不同，不触发“同表更新子查询”限制。随后 `MODIFY COLUMN` 保持 `VARCHAR(30) NOT NULL DEFAULT 'PENDING'`。 | 不新建表或索引。只修改已有列注释/定义。 | 列保持 `NOT NULL DEFAULT 'PENDING'`。历史无法还原的行写成 `'UNKNOWN'`，新行仍由应用写 `PENDING/APPROVED/REJECTED`。 | `WHERE reviewer_id IS NULL` 使应用已写入的新行不会被再次覆盖。`MODIFY COLUMN` 重复执行同一语句在 MySQL 上可重入，但 Flyway 成功后不会重跑。 | 是 | 是 | 是，只改审核状态字段 | 是 | `KnowledgeDocVersion.reviewStatus` / `reviewerId` 与列名一致 | **通过**。这是业务历史校准，不是权限迁移。 |
| V52 | `query_task.suggested_questions JSON NULL AFTER masked_fields` | 依赖 V26 已存在的 `masked_fields`。与 S1 无关。 | `ADD COLUMN ... JSON NULL` 需要 MySQL 5.7+，与项目一致。 | 仓库中无更早的 `suggested_questions` 列。`AFTER masked_fields` 只影响物理列顺序：V28 曾把 `prompt_versions` 插在 `masked_fields` 后，V33 又把 `degraded` 插在 `masked_fields` 后，因此真实列序会变成 `masked_fields → suggested_questions → degraded → ...`。MyBatis-Plus 按列名映射，不受序影响。 | 可空，无默认值，历史任务保持 `NULL`。 | **不可重入**：列已存在时第二次 `ADD COLUMN` 失败。只能依赖 Flyway 版本记录。 | 是 | 是 | 是 | 是 | `QueryTask.suggestedQuestions` 为 `String`，与其他 JSON 列一致 | **通过**。注意真实库尚未有此列，实体已映射。 |
| V54 | S1 配置基础：功能目录、角色、绑定、负责源、修订、审计、bootstrap 状态 | 独立新表，不修改旧表。必须在 V55/V57 之前。 | `CREATE TABLE IF NOT EXISTS`、`UNIQUE KEY`、`INDEX`、`INSERT ... ON DUPLICATE KEY UPDATE` 均为 InnoDB/utf8mb4 合法语法。 | `iam_s1_*` 前缀与现有表无冲突。功能码 54 条，与 `IamS1FunctionCatalog` 及设计文档一致。 | 关键列均 `NOT NULL`；`status`/`protected_role`/`built_in` 有默认值；bootstrap 初始 `PENDING`。 | 建表 `IF NOT EXISTS` 可重入。功能/角色 `ON DUPLICATE KEY UPDATE` 只更新目录字段，不把 bootstrap 状态打回 `PENDING`。`iam_s1_role_function` 按 `(role_id, function_id)` 去重。 | 是 | 是。注释写明不读取、迁移或删除旧权限事实；脚本中无旧表名。 | 是 | 是。只插入固定 54 码和内置 `IAM_S1_SYSTEM_ADMIN`，不从 `sys_role` 回填。 | `IamS1Function` / `IamS1Role` / `IamS1RoleFunction` / `IamS1UserRole` / `IamS1RoleDatasource` / `IamS1PermissionRevision` / `IamS1AuditEvent` / `IamS1BootstrapState` 字段与表列一致 | **通过**。真实执行后仍只有内置受保护角色，没有业务数据授权。 |
| V55 | 数据授权、明确字段、结构化记录条件、字段保护 | 逻辑上使用 V54 的 protocol/revision，但 **没有** 数据库外键，因此 SQL 不强制依赖 V54；B5 仍必须按编号顺序执行。 | 四张 `CREATE TABLE IF NOT EXISTS`。`TEXT` 存结构化 JSON，无手写 SQL 列。 | `iam_s1_data_grant*` / `iam_s1_row_condition` / `iam_s1_field_protection` 为新表。索引名均带 `iam_s1_` 前缀。 | `valid_from`、`effect`、`revision_no` 等为 `NOT NULL`；`valid_until` 可空表示未设到期。字段保护 `mask_policy` 可空。 | `IF NOT EXISTS` 可重入；不会插入任何授权行。 | 是。`grant_id` 只建普通索引，完整性由 Service 保证。 | 是 | 是 | 是。注释写明不回填旧 `datasource_access` / `datasource_access_policy`。 | `IamS1DataGrant` / `IamS1DataGrantColumn` / `IamS1RowCondition` / `IamS1FieldProtection` 与表列一致；JSON 条件以 `structuredValueJson` 映射 | **通过**。执行后授权表为空，问数默认拒绝，直到管理员按第 6 节初始化。 |
| V56 | `query_task` 增加 S1 执行证据列与索引 | 依赖 `query_task` 已有 `datasource_id`、`user_id`。不修改 V54/V55。 | 连续 `ADD COLUMN` + `ADD INDEX`。JSON 列可空。 | 新列名 `iam_protocol_version`、`active_metadata_snapshot_id`、`permission_revision`、`iam_execution_snapshot`、`iam_resource_request`、`iam_source_trace`、`iam_capabilities`、`iam_final_protection_status` 及索引 `idx_query_task_iam_protocol` 在既有 migration 中未出现。 | 全部可空，旧任务保持 `NULL`，不会被当成 S1 任务。 | **不可重入**：列或索引已存在时失败。 | 是 | 是。不读写旧权限表。 | 是。不改 `result_data` 等业务结果。 | 是 | `QueryTask` 已声明全部对应字段；JSON 以 `String` 存储 | **通过**。旧任务缺少协议版本，S1 读取路径必须拒绝。 |
| V57 | 访问申请与审批 | 只新增 S1 表；不改 V54/V55/V56。`generated_grant_id` 不建外键。 | 两张 `CREATE TABLE IF NOT EXISTS`。申请字段以 JSON 文本保存标识，不保存业务原值。 | `iam_s1_access_request` / `iam_s1_access_approval` 为新表；`uk_iam_s1_access_approval_request` 保证一申请一审批。 | 申请 `purpose`、`requested_columns_json`、`table_name` 等为 `NOT NULL`；审批的批准字段/期限在拒绝时可为 `NULL`。 | `IF NOT EXISTS` 可重入；不插入申请行。 | 是 | 是。不读取 `access_approval_request`。 | 是 | 是 | `IamS1AccessRequest` / `IamS1AccessApproval` 与表列一致 | **通过**。 |

### 3.2 审查备注（不阻断静态准备，真实执行时必须知道）

1. V51/V52/V56 对已有表做 `ALTER`/`UPDATE`，失败会停在该版本。修复后只能继续前向，禁止改已执行成功的 Flyway 文件内容。
2. V52/V56 不是幂等 DDL。维护窗口内若中途失败，以 `flyway_schema_history` 成功记录为准，不要手工重复 `ADD COLUMN`。
3. V54 插入固定 54 码后，真实 bootstrap 仍不会产生数据授权。系统管理员拥有后台功能，**不自动拥有业务问数范围**。
4. 测试环境 `application-test.yml` 关闭 Flyway，因此 `mvn test` 全绿仍然 **不是** 迁移正确性证据。
5. 仅查询 `flyway_schema_history` 不够。V52/V56 不可重入，MySQL DDL 通常不能依赖事务整体回滚；手工加列或 Flyway 失败留下的部分列不会出现在成功记录里，直接 migrate 会中途失败。真实 B5 开始后必须先通过第 4.4 节只读 SQL 门禁。

---

## 4. 备份与回退

以下命令全部参数化。禁止把真实密码、JWT、API key 写入本文、脚本输出或提交记录。

### 4.1 维护窗口开始前

由用户确认并填入执行记录（本轮全部为待填）：

| 参数 | 占位 | 必须由用户确认 |
| --- | --- | --- |
| 维护窗口起止 | `<WINDOW_START>` / `<WINDOW_END>` | 是 |
| 最终部署 SHA | `$B5_EXPECTED_SHA`，必须等于 `git rev-parse HEAD`，且工作树干净 | 是，写入备份、切换和验收记录 |
| MySQL 主机/库名 | `$DB_HOST` / `$DB_NAME` | 是 |
| MySQL 账号 | `$DB_USERNAME` | 是；密码只走环境变量或本地密钥，不入库 |
| 备份文件目录 | `$BACKUP_DIR` | 是 |
| 隔离演练空库名 | `$DRILL_DB`，不得等于 `$DB_NAME` | 是；仅用于恢复演练 |
| Redis 主机 | `$REDIS_HOST` | 是 |
| 首个新系统管理员 `userId` | 见第 5 节 | 是 |

### 4.2 参数化步骤

1. **维护窗口开始**
   公告停止写入。记录窗口时间、操作人和用户确认的 `$B5_EXPECTED_SHA`。

2. **停止应用写入**
   停止 Java 与 Python 应用进程。不要停止或重建共享 Docker 基础设施，除非用户明确要求。确认没有新的 `/api/query/**`、`/api/iam-s1/**`、后台写接口。

3. **MySQL 全量备份**（只示例，不执行）

禁止用 PowerShell `>` 或管道保存 dump：会把 `\n` 转成 `\r\n`，并可能写成 UTF-16，导致备份无法按原样恢复。必须让 `mysqldump` 自己写文件。

```powershell
$backupFile = Join-Path $env:BACKUP_DIR "dataocean-before-b5-$env:B5_EXPECTED_SHA.sql"
mysqldump --host=$env:DB_HOST --port=$env:DB_PORT --user=$env:DB_USERNAME --password --single-transaction --routines --triggers --events --default-character-set=utf8mb4 --result-file=$backupFile $env:DB_NAME
```

- 不要加 `--databases`。dump 里不得带 `CREATE DATABASE` / `USE $DB_NAME`，才能导入到干净的空库或演练库。
- `--events` 必须显式打开；`--routines`、`--triggers` 一并带上。
- `--default-character-set=utf8mb4` 必须与库字符集一致。
- 密码通过交互或环境提供，不要写进命令历史文档。

备份后必须完成下列校验，任一失败则停止，不得进入迁移：

```powershell
if (-not (Test-Path -LiteralPath $backupFile)) { throw "backup file missing" }
$item = Get-Item -LiteralPath $backupFile
if ($item.Length -le 0) { throw "backup file is empty" }
Get-FileHash -LiteralPath $backupFile -Algorithm SHA256
$headBytes = [System.IO.File]::ReadAllBytes($backupFile)[0..3]
if ($headBytes[0] -eq 0xFF -and $headBytes[1] -eq 0xFE) { throw "UTF-16 BOM detected; dump must not use PowerShell redirection" }
if ($headBytes[0] -ne 0x2D -or $headBytes[1] -ne 0x2D) { throw "dump must start with -- (mysqldump header)" }
Select-String -LiteralPath $backupFile -Pattern "^-- Dump completed" | Select-Object -Last 1
Select-String -LiteralPath $backupFile -Pattern "(?i)CREATE TABLE.*iam_s1_"
# 上一行在迁移前必须无匹配。出现 iam_s1_ 建表语句则停止。
```

把文件路径、字节大小、SHA256、`Dump completed` 是否存在写入切换记录。备份完成后立刻做第 4.3.2 节隔离恢复演练；演练未通过对生产库零写入，也不进入第 4.4 节。

4. **真实库只读 SQL 门禁**（必须完整执行第 4.4 节）
   只查 `flyway_schema_history` 不够。必须同时核对这些不可重入对象是否已被手工或失败迁移留下：`query_task.suggested_questions`、V56 的 8 个证据列及索引、全部 `iam_s1_%` 表。任一状态与预期不一致时 **停止，不执行 migrate**。本准备轮不得连接真实库；真实 B5 开始后，仅在用户提供连接时只读执行。

5. **记录并核对最终部署 SHA**

```powershell
git rev-parse HEAD
git status --short --branch
```

真实执行要求：`$env:B5_EXPECTED_SHA` 已由用户确认，`git rev-parse HEAD` 与它完全一致，工作树干净，且 HEAD 包含 `1a6e426`。将该 SHA 写入备份文件名、切换记录和验收记录。仅验证“包含 `1a6e426`”不足以证明当前 HEAD 就是批准构建。

6. **记录 Redis 相关 key 数量**（只读，不打印值）

```powershell
# 只统计数量，禁止 GET 值
redis-cli -h $env:REDIS_HOST -p $env:REDIS_PORT --scan --pattern "iam-s1:*" | Measure-Object
redis-cli -h $env:REDIS_HOST -p $env:REDIS_PORT --scan --pattern "jwt:blacklist:*" | Measure-Object
redis-cli -h $env:REDIS_HOST -p $env:REDIS_PORT --scan --pattern "user:token-version:*" | Measure-Object
```

若 Redis 需要密码，使用本地环境注入，不要把密码写进脚本仓库。

7. **执行仓库只读 preflight**
   运行 `scripts/iam-s1-b5-preflight.ps1`。静态检查必须通过，且 `B5_EXPECTED_SHA`、bootstrap `userId`（`^[1-9]\d*$`）已由用户确认。脚本不得连接业务库，不得做 DDL。第 4.4 节 SQL 门禁是独立步骤，不由本脚本代跑。

8. **失败时的停止点**

| 停止点 | 判定 | 动作 |
| --- | --- | --- |
| S0 备份前 | 无法停止写入、工作树不干净、HEAD ≠ `B5_EXPECTED_SHA`、缺少确认的 userId | 取消窗口，不碰数据库 |
| S1 备份后、迁移前 | 备份文件空、编码/校验失败、隔离演练对象不一致、第 4.4 节 SQL 门禁失败、仓库 preflight 失败 | 恢复服务到旧体系，不迁移 |
| S2 V51/V52 失败 | `flyway_schema_history` 出现失败行或应用无法启动 | 停止后续迁移；按 4.3.1 恢复到干净库；不手工改失败脚本后重跑已成功版本 |
| S3 V54～V57 失败 | S1 表不完整 | 视为未切换；按 4.3.1 恢复到干净库；继续旧体系 |
| S4 迁移成功、bootstrap 失败 | `iam_s1_bootstrap_state` 仍非对目标账号 `COMPLETED` | 未正式切换。可按 4.3.1 恢复备份并继续旧体系；不要用旧 `ADMIN`/`*` 补救 |
| S5 初始化未完成 | 新角色/负责源/数据授权清单有未确认项 | 不得开放正式入口；可按 4.3.1 回退 |
| S6 已产生新的 S1 业务操作 | 已有新授权、申请、S1 查询任务或管理员写操作 | **不能**用删除 `iam_s1_*` 表回退 |

### 4.3 回退边界

- **可以按备份回退并继续旧系统的条件**：migration / bootstrap / 新权限配置已经做完，但 **尚未正式切换**，且没有对真实用户开放新入口，也没有产生新的 S1 业务操作。回退必须走 4.3.1，不能覆盖式导入。
- **一旦正式产生新的 S1 业务操作**（新角色绑定、负责源、数据授权、访问申请、S1 查询任务、字段保护变更），不能简单删除新表回退。那些行已经是新体系事实。
- **禁止新旧权限双读兜底。** 一次 HTTP 请求只允许命中一套权限实现。S1 拒绝时不得调用旧 Resolver、旧 `PermissionCalculator` 或旧 JWT authorities。
- **禁止通过旧权限恢复新权限授权。** 回退到旧系统后，旧 `sys_role_permission` / `datasource_access` 不能用来重建 `iam_s1_*`。若将来再次切换，必须重新 bootstrap 并按第 6 节重新确认。

### 4.3.1 整库回退方式（禁止覆盖式导入）

`mysqldump` 默认会为 **dump 中存在的表** 写出 `DROP TABLE IF EXISTS`，但 **不会删除 dump 之后新建、且备份中不存在的表**。把 V50 备份直接导入仍含 `iam_s1_*` 的原库，这些新表会留下来，与“整库恢复”不一致。

禁止：

- 对仍有业务数据或备份后新建对象的目标库执行覆盖式导入（包括对非空原库 `SOURCE`、PowerShell 管道、`<` 重定向进原库）。
- 指望 dump 里的 `DROP TABLE` 自动清掉 `iam_s1_*`。

允许且必须二选一，并取得用户明确授权后才执行：

1. **恢复到预先建好的空库**（新库名，或已经是空库）。空库定义：无用户表、无 `iam_s1_%`、无业务 routines/events。
2. **先删除并重建目标库，再导入。** 仅在用户书面确认库名等于 `$DB_NAME` 后执行：

```sql
-- 必须由用户明确授权。执行前再次核对该名字等于确认的 $DB_NAME。
DROP DATABASE IF EXISTS confirmed_db_name;
CREATE DATABASE confirmed_db_name CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

导入必须指定 utf8mb4，并用 mysql 客户端 `SOURCE`，避免 PowerShell 重定向：

```powershell
$backupUnix = ($backupFile -replace '\\','/')
mysql --host=$env:DB_HOST --port=$env:DB_PORT --user=$env:DB_USERNAME --password --default-character-set=utf8mb4 --database=$env:DB_NAME --execute="SOURCE '$backupUnix'"
```

导入后立刻核对该库 **不存在** 任何 `iam_s1_%` 表，Flyway 最大成功版本回到 V50，且 `query_task.suggested_questions` 与 V56 证据列均不存在。对象集合必须与备份前记录的清单一致。

### 4.3.2 隔离恢复演练及对象核对

真实 B5 在通过备份校验之后、对生产库执行第 4.4 节 SQL 门禁之前，必须先在 **隔离空库** 演练一次恢复。本准备轮不连接真实库，因此也不做这次演练。

约束：

- `$DRILL_DB` 由用户确认，且不得等于 `$DB_NAME`。
- 只允许 `CREATE DATABASE` 一个空的演练库、导入备份、只读核对、然后 `DROP DATABASE` 该演练库。
- 不得对 `$DB_NAME` 做 DROP、导入或任何写操作。
- 导入同样禁止 PowerShell `>` / 管道。

```powershell
if ($env:DRILL_DB -notmatch '^[A-Za-z0-9_]+$') { throw "invalid DRILL_DB" }
if ($env:DRILL_DB -eq $env:DB_NAME) { throw "DRILL_DB must not equal DB_NAME" }
mysql --host=$env:DB_HOST --port=$env:DB_PORT --user=$env:DB_USERNAME --password --execute="CREATE DATABASE $env:DRILL_DB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
$backupUnix = ($backupFile -replace '\\','/')
mysql --host=$env:DB_HOST --port=$env:DB_PORT --user=$env:DB_USERNAME --password --default-character-set=utf8mb4 --database=$env:DRILL_DB --execute="SOURCE '$backupUnix'"
```

演练库必须全部成立，否则停止，不进入 4.4，不 migrate：

| 核对 | 预期 |
| --- | --- |
| 演练库字符集 | `utf8mb4` / `utf8mb4_unicode_ci` |
| `iam_s1_%` 表 | 0 |
| Flyway 最大成功版本 | 50，且无失败记录 |
| `query_task.suggested_questions` | 不存在 |
| V56 的 8 个证据列与 `idx_query_task_iam_protocol` | 全部不存在 |
| V51 依赖的知识表列、`query_task.masked_fields` / `datasource_id` / `user_id` | 存在 |
| 表/例程/事件/触发器名字集合 | 与备份前对 `$DB_NAME` 记录的只读清单一致 |
| dump 校验和 | 与备份记录中的 SHA256 相同（文件未被改写） |

备份前只读记录对象清单（对 `$DB_NAME`，不写库）：

```sql
SELECT TABLE_NAME FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'
ORDER BY TABLE_NAME;
SELECT ROUTINE_TYPE, ROUTINE_NAME FROM information_schema.ROUTINES
WHERE ROUTINE_SCHEMA = DATABASE()
ORDER BY ROUTINE_TYPE, ROUTINE_NAME;
SELECT EVENT_NAME FROM information_schema.EVENTS
WHERE EVENT_SCHEMA = DATABASE()
ORDER BY EVENT_NAME;
SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA = DATABASE()
ORDER BY TRIGGER_NAME;
```

演练通过后，经用户确认再 `DROP DATABASE` 演练库。随后才允许对生产库跑第 4.4 节只读 SQL 门禁。

### 4.4 真实库只读 SQL 门禁（migrate 前必须）

V52、V56 都不可重入，而 MySQL DDL 通常不能依赖事务整体回滚。如果历史上有人手工加过列，或 Flyway 失败后留下部分列，再直接 migrate 会中途失败。因此 **不能只查 `flyway_schema_history`**，必须核对这些对象在真实库中的实际存在性。

边界：

- 本准备轮 **不得连接真实库**。
- 真实 B5 开始后，仅在用户提供数据库连接时只读执行。
- 使用业务库只读账号或至少不执行 DDL/DML 的会话。密码不入库、不写入本文。
- 连接后 `USE $DB_NAME;`。下面用 `DATABASE()` 限定当前库。
- **任一检查 `ok = 0`、语句失败、行数不足或结果与预期不一致：立即停止，不执行 migrate。**

```sql
-- B5 live-DB read-only preflight. Stop unless every row has ok = 1.
-- Do not run this query in the preparation round.

SELECT check_id, expected, actual, ok
FROM (
    SELECT
        'flyway_max_success_is_v50' AS check_id,
        '50' AS expected,
        CAST(IFNULL((
            SELECT MAX(CAST(version AS UNSIGNED))
            FROM flyway_schema_history
            WHERE success = 1 AND version REGEXP '^[0-9]+$'
        ), -1) AS CHAR) AS actual,
        CASE WHEN (
            SELECT MAX(CAST(version AS UNSIGNED))
            FROM flyway_schema_history
            WHERE success = 1 AND version REGEXP '^[0-9]+$'
        ) = 50 THEN 1 ELSE 0 END AS ok
    UNION ALL
    SELECT
        'flyway_no_failed_records',
        '0',
        CAST((SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0) AS CHAR),
        CASE WHEN (SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0) = 0 THEN 1 ELSE 0 END
    UNION ALL
    SELECT
        'query_task.suggested_questions_absent',
        '0',
        CAST((
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'query_task'
              AND COLUMN_NAME = 'suggested_questions'
        ) AS CHAR),
        CASE WHEN (
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'query_task'
              AND COLUMN_NAME = 'suggested_questions'
        ) = 0 THEN 1 ELSE 0 END
    UNION ALL
    SELECT
        'v56_evidence_columns_absent',
        '0',
        CAST((
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'query_task'
              AND COLUMN_NAME IN (
                  'iam_protocol_version',
                  'active_metadata_snapshot_id',
                  'permission_revision',
                  'iam_execution_snapshot',
                  'iam_resource_request',
                  'iam_source_trace',
                  'iam_capabilities',
                  'iam_final_protection_status'
              )
        ) AS CHAR),
        CASE WHEN (
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'query_task'
              AND COLUMN_NAME IN (
                  'iam_protocol_version',
                  'active_metadata_snapshot_id',
                  'permission_revision',
                  'iam_execution_snapshot',
                  'iam_resource_request',
                  'iam_source_trace',
                  'iam_capabilities',
                  'iam_final_protection_status'
              )
        ) = 0 THEN 1 ELSE 0 END
    UNION ALL
    SELECT
        'v56_idx_query_task_iam_protocol_absent',
        '0',
        CAST((
            SELECT COUNT(DISTINCT INDEX_NAME)
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'query_task'
              AND INDEX_NAME = 'idx_query_task_iam_protocol'
        ) AS CHAR),
        CASE WHEN (
            SELECT COUNT(DISTINCT INDEX_NAME)
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'query_task'
              AND INDEX_NAME = 'idx_query_task_iam_protocol'
        ) = 0 THEN 1 ELSE 0 END
    UNION ALL
    SELECT
        'iam_s1_tables_absent',
        '0',
        CAST((
            SELECT COUNT(*)
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME LIKE 'iam_s1\_%' ESCAPE '\\'
        ) AS CHAR),
        CASE WHEN (
            SELECT COUNT(*)
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME LIKE 'iam_s1\_%' ESCAPE '\\'
        ) = 0 THEN 1 ELSE 0 END
    UNION ALL
    SELECT
        'v51_knowledge_tables_and_columns_present',
        '7',
        CAST((
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND (
                  (TABLE_NAME = 'knowledge_doc_version' AND COLUMN_NAME IN ('review_status', 'reviewer_id'))
                  OR (TABLE_NAME = 'knowledge_review_task' AND COLUMN_NAME IN ('doc_version_id', 'review_status', 'reviewer_id'))
              )
        ) + (
            SELECT COUNT(*)
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME IN ('knowledge_doc_version', 'knowledge_review_task')
        ) AS CHAR),
        CASE WHEN (
            SELECT COUNT(*)
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME IN ('knowledge_doc_version', 'knowledge_review_task')
        ) = 2
        AND (
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND (
                  (TABLE_NAME = 'knowledge_doc_version' AND COLUMN_NAME IN ('review_status', 'reviewer_id'))
                  OR (TABLE_NAME = 'knowledge_review_task' AND COLUMN_NAME IN ('doc_version_id', 'review_status', 'reviewer_id'))
              )
        ) = 5 THEN 1 ELSE 0 END
    UNION ALL
    SELECT
        'query_task_masked_fields_datasource_id_user_id_present',
        '3',
        CAST((
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'query_task'
              AND COLUMN_NAME IN ('masked_fields', 'datasource_id', 'user_id')
        ) AS CHAR),
        CASE WHEN (
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'query_task'
              AND COLUMN_NAME IN ('masked_fields', 'datasource_id', 'user_id')
        ) = 3 THEN 1 ELSE 0 END
    UNION ALL
    SELECT
        'mysql_json_supported',
        'JSON type available',
        VERSION(),
        CASE WHEN CAST('[]' AS JSON) IS NOT NULL THEN 1 ELSE 0 END
) AS checks
ORDER BY check_id;
```

判定表：

| 检查 | 必须成立，否则停止 |
| --- | --- |
| Flyway 最大成功版本必须为 V50 | `flyway_max_success_is_v50.ok = 1` |
| 不得存在失败记录 | `flyway_no_failed_records.ok = 1` |
| `query_task.suggested_questions` 必须不存在 | 计数为 0。部分存在也停止 |
| V56 的 8 个 S1 证据列及索引必须全部不存在 | 8 列与 `idx_query_task_iam_protocol` 计数均为 0。缺一部分或多一部分都停止 |
| `iam_s1_%` 表必须全部不存在 | `TABLE_NAME LIKE 'iam_s1\_%'` 计数为 0 |
| V51 依赖的知识表和列必须存在 | `knowledge_doc_version`、`knowledge_review_task` 两表存在；列 `review_status`、`reviewer_id`、`doc_version_id` 按上表齐全 |
| `query_task.masked_fields`、`datasource_id`、`user_id` 必须存在 | 3 列都在 |
| MySQL 版本必须支持 JSON | `CAST('[]' AS JSON)` 成功。语句失败视为不支持，停止 |

V56 的 8 个证据列：`iam_protocol_version`、`active_metadata_snapshot_id`、`permission_revision`、`iam_execution_snapshot`、`iam_resource_request`、`iam_source_trace`、`iam_capabilities`、`iam_final_protection_status`。

`CAST('[]' AS JSON)` 在不支持 JSON 的服务器上会直接报错。报错与 `ok = 0` 同等：停止，不执行 migrate。

---

## 5. bootstrap 输入

首个新系统管理员 **必须由用户显式给出 `userId`**。不得从旧 `ADMIN` 角色、旧角色名、旧 `*` 权限、固定 `id=1` 或历史管理员名称推导。

### 5.1 执行前必须由用户确认

| 输入 | 说明 | 本轮状态 |
| --- | --- | --- |
| 首个新系统管理员 `userId` | 正整数，对应 `sys_user.id`，正则 `^[1-9]\d*$`，**不接受 0** | **待用户确认，禁止填写虚构值** |
| 账号必须存在、启用且未删除 | bootstrap 用 `countEnabledUser(userId) == 1` 校验 | 待用户在真实库只读核验 |
| 是否已有其他受保护管理员 | 首次应为否。V54 只插入角色，不插入绑定。若 `COMPLETED` 已存在且目标不同，服务会拒绝更换 | 待真实库核验；当前真实库无该表 |
| bootstrap 配置方式 | 只允许启动参数或环境变量，不提供 HTTP 后门 | 见下表 |
| 预期一次性状态 | 成功后 `iam_s1_bootstrap_state.state = COMPLETED`，`target_user_id` 等于确认的 userId，幂等重跑同一 userId 返回已完成 | 待执行后核验 |
| 完成后如何关闭入口 | 去掉 bootstrap 开关与 userId，恢复普通 Web 启动 | 见 5.3 |

### 5.2 配置方式（与代码一致）

判定入口：`IamS1BootstrapMode`。

| 方式 | 启用 | 目标账号 |
| --- | --- | --- |
| 环境变量 | `IAM_S1_BOOTSTRAP_ENABLED=true` | `IAM_S1_BOOTSTRAP_USER_ID=<userId>` |
| Spring 属性 | `iam.s1.bootstrap.enabled=true` | `iam.s1.bootstrap.user-id=<userId>` |
| 命令行 | `--IAM_S1_BOOTSTRAP_ENABLED=true` 或 `--iam.s1.bootstrap.enabled=true` | `--IAM_S1_BOOTSTRAP_USER_ID=<userId>` 或 `--iam.s1.bootstrap.user-id=<userId>` |

行为约束（已由现有测试钉住，本轮不启动）：

- `IamS1BootstrapEnvironmentPostProcessor` 在 bootstrap 开启时把 Web 设为 `NONE`，没有 HTTP 初始化接口。
- `IamS1NonBootstrapCondition` 在 bootstrap 期间关闭定时任务。
- 缺少合法 userId 直接失败。
- 目标账号不存在、已删除或未启用直接失败。
- 已 `COMPLETED` 时，只有同一 userId 且绑定仍有效才视为幂等成功；更换目标账号被拒绝。
- 不根据旧角色或旧权限码选择账号。

示例（占位，不执行）：

```powershell
$env:IAM_S1_BOOTSTRAP_ENABLED = "true"
$env:IAM_S1_BOOTSTRAP_USER_ID = "<USER_CONFIRMED_ID>"
# 然后以非 Web 方式启动一次 Java 进程完成绑定；成功后必须关闭这两个变量。
```

### 5.3 关闭入口

bootstrap 成功并完成第 8 节切换验收后：

1. 从运行环境删除 `IAM_S1_BOOTSTRAP_ENABLED` / `IAM_S1_BOOTSTRAP_USER_ID` 及对应 Spring 属性。
2. 确认后续普通启动 `IamS1BootstrapMode.isEnabled` 为 false，Web 入口恢复。
3. 只读确认 `iam_s1_bootstrap_state` 仍为 `COMPLETED` 且 `target_user_id` 未变。
4. 不得保留可长期绕过正常权限管理的启动参数。

---

## 6. 新权限初始化清单

本表是 **模板**。所有带“待用户确认”的格子本轮保持空白，禁止用旧 `ADMIN`/`ANALYST`/`DATA_MANAGER` 名称或真实库里未确认的数据源 ID 填入。

内置且不可当作业务模板冒充的角色：

| 角色 | 用途 | 功能码 | 负责源 | 数据授权 | 来源 |
| --- | --- | --- | --- | --- | --- |
| `IAM_S1_SYSTEM_ADMIN`（系统管理员） | 唯一受保护内置角色 | V54 固定 54 码全部授予该角色 | 后台视为全部启用数据源 | **不自动拥有业务数据** | 仅允许 bootstrap 绑定首个管理员；禁止复制该角色 |

推荐业务模板（来自 `IamS1CapabilityServiceImpl.roleTemplates()`，创建时仍须用户确认名称、成员和范围）：

| 模板代码 | 中文名称 | 固定功能码组合 | 问数数据从哪来 | 后台负责源 | 本轮填写 |
| --- | --- | --- | --- | --- | --- |
| `QUERY_USER` | 普通问数用户 | `query:use` | 部门默认或明确授权 | 不需要 | 待用户确认是否创建、绑定哪些人 |
| `BUSINESS_ANALYST` | 业务分析人员 | `query:use`、`query:sql:view`、`query:export` | 部门、角色或个人授权 | 不需要 | 待用户确认 |
| `DATA_GOVERNANCE` | 数据治理人员 | `metadata:view` 及 `governance:*` 查看/检查/问题/规则/字段 | 问数另行授权 | 系统管理员选择负责源 | 待用户确认 |
| `SEMANTIC_MAINTAINER` | 语义维护人员 | `metadata:view`、`glossary:view/manage`、`knowledge:view/manage` | 不默认问数 | 系统管理员选择负责源 | 待用户确认 |
| `REVIEW_PUBLISHER` | 审核发布人员 | 快照/术语/知识的查看、审核、发布 | 不默认维护内容、不默认问数 | 系统管理员选择负责源 | 待用户确认 |

### 6.1 必须由用户确认后才能写入的行

复制下表到切换执行记录，逐行确认。未确认的行不得写入。

| 字段 | 值 | 必须由用户确认 |
| --- | --- | --- |
| 新角色名称和用途 |  | 是 |
| 固定功能码组合 | 只能选自 54 码目录 | 是 |
| 用户角色绑定（`userId` + `roleId`） | 账号必须启用且未删除 | 是 |
| 每个绑定负责的数据源 | 同一 `user_role` 绑定内的 datasourceId 列表；全局功能除外 | 是 |
| 主体类型 | `USER` / `ROLE` / `DEPARTMENT` | 是 |
| 数据源 / 已发布快照 / 表 |  | 是 |
| 明确字段（`column_meta_id` 列表） | 空列表对表级 DENY 有特殊语义，不能省略确认 | 是 |
| 记录条件 | 结构化条件；禁止手写 SQL | 是 |
| 有效期 `valid_from` / `valid_until` |  | 是 |
| 效果 | `ALLOW` 或 `DENY` | 是 |
| 字段保护 | 表、列、`protection_level`、`mask_policy` | 是 |
| 查询 SQL 查看 / CSV 导出 | 只通过角色功能码 `query:sql:view` / `query:export`，不在数据授权里重复配置 | 是 |
| 审批人 | 必须在同一绑定上拥有 `security:approval:review` 与该申请的负责源 | 是 |
| 申请可审批的负责源 |  | 是 |

授权模板仅作界面提示，不能代替上表确认：`DEPARTMENT_SELF`、`DEPARTMENT_TREE`、`ROLE_LONG_TERM`、`USER_TEMPORARY_30`。

---

## 7. B5 真实验收矩阵

图例：

- **静**：静态检查（源码、手册、preflight）
- **自**：自动化测试（H2/Mockito/Vitest/pytest，不连真实 MySQL）
- **库**：真实数据库核验
- **服**：真实服务运行
- **浏**：真实浏览器验收

本准备轮只完成“静/自”。标为“库/服/浏”的项在真实 B5 之前全部为 **未执行**。自动化通过不得改写为 B5 已完成。

### 7.1 身份 × 场景

| 身份 | 七个后台业务域 | 用户/部门/S1 角色与负责源 | 数据授权、字段保护、实际权限预览 | 访问申请与审批 | 独立安全问数 | RAG 过滤 | SQL AST | 行条件 | 最终脱敏 | SQL 查看 | CSV 导出 | 历史/反馈/SSE | 列表/详情/批量/统计 | 直接 API 负向调用 | 权限撤回后当前权限复查 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 新系统管理员 | 静/自；库/服/浏待做 | 静/自；库/服/浏待做 | 静/自；库/服/浏待做 | 静/自；库/服/浏待做 | 静/自（无数据授权则问数拒绝）；库/服/浏待做 | 自；库/服待做 | 自；服待做 | 自；库/服待做 | 自；库/服/浏待做 | 静/自；服/浏待做 | 静/自；服/浏待做 | 自；服/浏待做 | 静/自；服/浏待做 | 静/自；服待做 | 服/浏待做 |
| 普通问数用户 | 后台入口应拒绝（无后台功能）；静/自；服/浏待做 | 不可管理组织；静/自；服/浏待做 | 本人预览可见已授权范围；静/自；服/浏待做 | 可申请不可审批他人；静/自；服/浏待做 | 仅授权范围；静/自；服/浏待做 | 自；服待做 | 自；服待做 | 自；服待做 | 自；服/浏待做 | 无 `query:sql:view` 应 403；静/自；服/浏待做 | 无 `query:export` 应 403；静/自；服/浏待做 | 自；服/浏待做 | 不适用后台列表 | 服待做 | 服/浏待做 |
| 负责单个数据源的后台管理员 | 只看到该源工作区；静/自；服/浏待做 | 不可把负责源扩到未授权源；静/自；服/浏待做 | 只能配该源；静/自；服/浏待做 | 只能审该源；静/自；服/浏待做 | 问数仍看数据授权而非负责源；静/自；服/浏待做 | 服待做 | 服待做 | 服待做 | 服/浏待做 | 取决于角色功能码 | 取决于角色功能码 | 服/浏待做 | 列表 SQL 下推该源；静/自；服/浏待做 | 跨源 API 403；服待做 | 撤负责源后立即 403；服待做 |
| 跨多个负责源的管理员 | 多源工作区可见；静/自；服/浏待做 | 同一绑定内多源；静/自；服/浏待做 | 不可用源 A 的功能操作源 B；静/自；服/浏待做 | 队列只含其负责源；静/自；服/浏待做 | 同左 | 服待做 | 服待做 | 服待做 | 服/浏待做 | 同角色码 | 同角色码 | 服/浏待做 | 多源 IN 列表；静/自；服/浏待做 | 服待做 | 服待做 |
| 只有查看权的用户 | 无写按钮/写 API 403；静/自；服/浏待做 | 可看不可改；静/自；服/浏待做 | 可看不可写保护；静/自；服/浏待做 | 可看自己申请，不可审批；静/自；服/浏待做 | 若无 `query:use` 则不可问 | 若可问则过滤；服待做 | 服待做 | 服待做 | 服/浏待做 | 无码则 403 | 无码则 403 | 读路径复查；服待做 | 空范围空页而非全库；静/自；服/浏待做 | 写接口 403；服待做 | 服待做 |
| 没有权限的启用用户 | 后台拒绝，问数拒绝；静/自；服/浏待做 | 401/403 | 无授权预览为空或拒绝 | 申请须至少 `query:use` | 拒绝 | 不检索 | 不执行 | 不执行 | 无结果 | 403 | 403 | 403 | 空页或 403 | 服待做 | 不适用 |
| 被禁用用户 | 登录拒绝；静/自；服待做 | 绑定不生效 | 不生效 | 不生效 | 不生效 | 不执行 | 不执行 | 不执行 | 无 | 403 | 403 | 403 | 403 | 服待做 | 禁用后已有任务读取拒绝；服待做 |
| 权限被撤回后的用户 | 后台能力立即消失；静/自；服/浏待做 | 绑定停用后负责源失效 | 授权 `REVOKED`/过期后预览为空 | 已批准但授权撤销后不能再查 | 当前权限复查拒绝 | 不再返回已撤销资源 | AST 按新快照拒绝 | 新条件生效 | 再脱敏或拒绝 | `query:sql:view` 撤销后 403 | `query:export` 撤销后 403 | 历史/SQL/CSV/反馈/SSE 一律按当前权限；自已有，服/浏待做 | 列表不再含该源 | 直接 API 403 | **B5 必验** |

### 7.2 能力清单（按证据类型）

| 验收项 | 静 | 自 | 库 | 服 | 浏 |
| --- | --- | --- | --- | --- | --- |
| 七个后台业务域入口与侧栏 | 路由/`ADMIN_WORKSPACES` 已对照 | 前端能力测试存在 | 不涉及 | 待 B5 | 待 B5：工作台、数据接入、数据资产、数据治理、语义中心、权限与组织、运营与平台 |
| 用户、部门、S1 角色与负责源 | 手册第 6 节模板 | Java 角色/绑定测试 | 待：只读确认无旧表回填 | 待 | 待：`/admin/access/iam-organization`；旧 `/admin/access/organization` 在切换时移除 |
| 数据授权 / 字段保护 / 实际权限预览 | 契约与 Resolver 静态扫描 | B2 测试 | 待：表空或仅含确认行 | 待 | 待：`/admin/access/iam` |
| 访问申请与审批 | V57 审查 | B4 申请测试 | 待 | 待 | 待：`/admin/access/iam-approvals` |
| 独立安全问数 | `/query/iam-s1` 与 `/api/iam-s1/query` | B3 测试 | 待 | 待 | 待 |
| RAG 过滤 | 设计与内部路径 | Python/Java 检索过滤测试 | 待真实 Milvus | 待 | 待：无权字段不出现在可问范围 |
| SQL AST | 不在 migration 中 | Python AST 套件 | 不直接改库 | 待真实只读库 | 待：危险 SQL 被拒 |
| 行条件 | V55 结构化列 | Java/Python 注入测试 | 待 | 待 | 待：只能看到条件内记录 |
| 最终脱敏 | Java `deriveOutputMasks` | B3.3–B3.6 测试 | 待 | 待 | 待：掩码后无原值 |
| SQL 查看 | 功能码独立 | 接口测试 | 待 | 待 | 待 |
| CSV 导出 | 功能码独立 | 接口测试 | 待 | 待 | 待 |
| 历史、反馈、SSE | 当前权限复查设计 | B3.1/B3.2 测试 | 待 | 待 | 待 |
| 列表、详情、批量、统计 | 覆盖扫描 + SQL 下推 | 批次 3～6 测试 | 待 | 待 | 待：空范围空页，指定无权资源 403 而非空页伪装 |
| 直接 API 负向调用 | 例外清单 77 条冻结 | 覆盖扫描、切面代理测试 | 待 | 待：无码、错源、未登录、旧 JWT | 待 |
| 权限撤回后复查 | 设计冻结 | 部分单元测试 | 待 | **必做** | **必做** |
| `@Aspect` 真实代理 | 源码同时有 `@Aspect` 与 `@Component` | `migratedControllersAreActuallyProxiedSoTheAnnotationsRun` 等 | 不涉及 | 待确认生产同样代理 | 不直接测 |
| 旧 JWT / 旧权限不能授权 | 禁止映射已写入 B0 | 静态扫描 Resolver 无旧表 | 待确认未回填 | **必做**：旧 token 缺协议字段被拒 | 待 |
| V51～V57 真实执行 | 本手册第 3 节 | **无**（测试关 Flyway） | **B5 必做** | 启动后版本=57 | 不直接测 |
| 真实库 SQL 门禁 | 第 4.4 节只读 SQL | 脚本不连库 | **B5 必做，失败则不 migrate** | 不涉及 | 不涉及 |
| 最终部署 SHA | `B5_EXPECTED_SHA` = `git rev-parse HEAD` 且工作树干净 | preflight 校验 | 写入备份/切换/验收记录 | 部署该 SHA | 证据目录记录同一 SHA |

---

## 8. 正式切换步骤（只写不执行）

只有第 5、第 6 节用户输入已确认，第 4 节备份成功，仓库 preflight 静态检查通过，第 4.4 节 SQL 门禁全部 `ok = 1`，`HEAD == B5_EXPECTED_SHA` 且工作树干净，维护窗口已开始，才能执行下列步骤。本准备轮 **停止在手册**，不 migrate。

1. **最终备份**
   按 4.2 用 `mysqldump --result-file` 再做一次 MySQL 全量备份（含 `--events`、utf8mb4），文件名包含 `B5_EXPECTED_SHA`。完成校验和、第 4.3.2 节隔离恢复演练、第 4.4 节 SQL 门禁，记录 Flyway=V50、确认 SHA、Redis key 计数。禁止 PowerShell `>` 保存 dump。

2. **部署已确认 SHA 的构建**
   部署 `B5_EXPECTED_SHA`。该 SHA 必须包含 `1a6e426`，但“包含 `1a6e426`”本身不够，当前 HEAD 必须等于用户批准的 SHA。此时仍不要对用户开放新入口。旧体系进程应已停止写入。

3. **应用 migration**
   仅当第 4.4 节门禁通过后，以 `flyway.enabled=true` 启动一次只做升级的过程，或使用受控 Flyway 命令。顺序必须是 V51、V52、V54、V55、V56、V57。禁止开启 `outOfOrder` 去补 V53。失败立即进入 4.2 停止点。

4. **执行 bootstrap**
   使用用户确认的 `userId`，按第 5 节非 Web 启动。确认 `COMPLETED`、审计事件存在、目标账号绑定受保护系统管理员角色。失败则按 4.3.1 恢复到干净库，不改用旧权限补救。

5. **初始化新角色、负责源和数据授权**
   仅写入第 6 节已确认的行。系统管理员仍须另配业务数据授权才能问数。禁止从旧表拷贝。

6. **强制用户重新登录**
   提升会话世代 / token 版本，作废旧 JWT。旧 claim 中的 `roles`/`permissions`/`*` 不得进入 S1 Resolver。

7. **移除旧组织导航和旧路由**
   从正式侧栏移除 `/admin/access/organization`（当前标签为“组织与角色（旧）”）及依赖旧权限树的入口。保留文件删除留给 B6。切换后正式入口只有 `/admin/access/iam-organization`、`/admin/access/iam`、`/admin/access/iam-approvals` 与 `/query/iam-s1`。

8. **验证全部 S1 页面和 API**
   按第 7 节矩阵跑库、服务、浏览器三项。七个业务域、列表/详情/批量/统计、直接 API 负向调用都要留下证据。

9. **验证旧 JWT / 旧权限不能授予访问**
   使用切换前签发的 token、只持有旧同名码的账号、空 S1 绑定的启用用户，访问 `/api/admin/**` 与 `/api/iam-s1/**` 均应失败。

10. **记录切换时间、确认 SHA 和验收证据**
    写入状态文档与 `output/playwright/` 证据目录，三处都使用同一个 `B5_EXPECTED_SHA`。明确区分自动化与真实验收。

11. **切换成功后关闭 bootstrap**
    按 5.3 删除启动开关。

12. **只有复验通过才能进入 B6**
    B6 才删除旧 Role/Permission/DatasourcePermission/AccessPolicy/AccessApproval 入口与旧权限表。未通过则停留在 B5，必要时按第 4 节回退；不得开始删除旧权限。

---

## 9. B5 执行前仍需用户提供的输入

本轮未向用户索取真实值，以下全部保持待确认：

1. 首个新系统管理员的明确 `userId`（`^[1-9]\d*$`，存在、启用、未删除；不接受 0）。
2. 最终部署 SHA（`B5_EXPECTED_SHA`，必须等于执行时 `git rev-parse HEAD`）。**不要批准 `17f2b96`。** 本轮手册修复提交后产生新的候选 SHA，须由用户另行决定是否批准。
3. 该账号是否已有其他受保护管理员绑定（预期首次为否）。
4. 维护窗口起止时间。
5. MySQL 全量备份目录、演练空库名 `$DRILL_DB`（不得等于 `$DB_NAME`），以及备份文件 SHA256（备份与演练在真实 B5 执行，不在本轮做）。
6. 真实库只读连接（仅用于第 4.4 节 SQL 门禁；必须先完成 4.3.2 演练。本准备轮不连接）。
7. 第 6 节每一行新角色、绑定、负责源、数据授权、字段保护、审批人。
8. 正式切换时由谁操作、证据目录路径。

在这些输入给出之前，不得执行真实 migration、bootstrap、部署或切换。覆盖式导入备份不算回退。
