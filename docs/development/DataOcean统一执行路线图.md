# DataOcean 统一执行路线图

> **唯一执行文档** — 合并自：重构实施指南 + 元数据治理深度改造方案
>
> 借鉴 OpenMetadata（https://github.com/open-metadata/OpenMetadata）
>
> 更新日期：2026-06-13 | 最后完善：2026-06-13

---

## 文档说明

**这是唯一需要看的执行文档。** 其他开发文档作为历史参考保留，实际执行以本文档为准。

**与后续开发.md 的关系**：本文档只执行重构任务；重构全部完成并验收通过后，本文档执行结束。后续新增功能另见 `docs/development/后续开发.md`。

---

## 〇、架构设计决策

### 0.1 OpenMetadata 借鉴说明

本方案借鉴 OpenMetadata（https://github.com/open-metadata/OpenMetadata）的核心架构设计，但根据 DataOcean 的 NL2SQL 场景做了裁剪：

| OpenMetadata 设计 | DataOcean 采纳方式 | 采纳原因 |
|-------------------|-------------------|----------|
| **Entity-Relationship 模型**（typed edges） | `metadata_entity` + `metadata_relationship` 两张表 | 统一存储所有实体关系（血缘、标签、术语、外键），比分散在各表更灵活 |
| **全限定名 FQN**（dot-separated path） | `datasource.db.table.column` 格式 | 唯一标识实体，解决跨数据源歧义，支持搜索和血缘图谱展示 |
| **Glossary 三级结构**（Glossary→Term→嵌套 Term） | 同样采用三级结构 | 术语有层级（如"财务指标"→"营收"→"月度营收"），支持同义词扩展 |
| **Classification→Tag 两级标签** | 同样采用两级结构 | PII 等分类需要层级（PII.身份证号），比扁平标签更有语义 |
| **Policy→Rule 权限模型**（Resource+Operation+Effect） | 在现有 RBAC 基础上增加策略优先级 | 现有模型已够用，只补优先级和时间条件 |
| **Kafka 事件总线** | 不采纳，用 MySQL 事件表替代 | DataOcean 规模不需要 Kafka，MySQL 够用 |
| **Elasticsearch 搜索** | 不采纳，用 MySQL 全文索引替代 | 不引入额外中间件，FULLTEXT INDEX 够用 |
| **OpenLineage 集成** | 不采纳，用 sqlglot 自提取 | DataOcean 只需 NL2SQL 查询范围的血缘 |

### 0.2 端到端闭环流程

元数据治理不是孤立的模块，它驱动整个 NL2SQL 链路：

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         元数据采集与治理                                  │
│                                                                         │
│  数据源 ──→ JDBC 采集 ──→ 快照存储 ──→ 快照发布 ──→ Entity 写入         │
│                              │                        │                 │
│                              ↓                        ↓                 │
│                         质量检查                FQN + 关系建立            │
│                              │                        │                 │
│                              ↓                        ↓                 │
│                    ┌─→ 治理 Issue ──→ 人工处理 ──→ 治理状态更新          │
│                    │                                                    │
│                    ├─→ AI 标签推断 ──→ 人工确认 ──→ 标签生效             │
│                    │                                                    │
│                    └─→ 术语表创建 ──→ 人工审批 ──→ 术语生效              │
│                              │                        │                 │
│                              ↓                        ↓                 │
│                    ┌─────────────────────────────────────┐              │
│                    │ Entity + 标签 + 术语 → RAG 向量化    │              │
│                    │ → Milvus 存储                        │              │
│                    └─────────────────────────────────────┘              │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ↓
┌─────────────────────────────────────────────────────────────────────────┐
│                         NL2SQL 查询链路                                  │
│                                                                         │
│  用户提问 → 术语匹配(同义词扩展) → RAG 检索 → SQL 生成                   │
│                                              │                          │
│                                              ↓                          │
│                                    SQL AST 校验 + 权限过滤               │
│                                              │                          │
│                                              ↓                          │
│                                    沙箱执行 + 结果脱敏                    │
│                                              │                          │
│                                              ↓                          │
│                                    结果返回 + 用户反馈                    │
│                                              │                          │
│                                              ↓                          │
│                                    置信度调整 → 影响下次 RAG 排序         │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 0.3 AI + Human 协作模型

DataOcean 的治理模式是 **AI 执行 + 人工审查 + 系统生效**：

| 环节 | AI 自动执行 | 人工审查/决策 | 系统自动生效 |
|------|------------|--------------|-------------|
| **标签管理** | AutoTagger 基于列名模式自动推断 PII/业务域标签 | 管理员确认/修正推断结果 | 标签写入 relationship，触发权限联动（PII→自动脱敏） |
| **术语管理** | — | 业务人员创建术语、填写同义词、关联物理列 | 术语审批通过后生效，RAG 检索时增加权重 |
| **质量治理** | 11 条规则 + 4 条数据级规则自动检查，生成 Issue | 管理员确认/驳回/分配 Issue | Issue 关闭后治理状态更新，BLOCKED/DEPRECATED 自动阻断查询 |
| **血缘管理** | sqlglot 自动提取查询级列引用 | 管理员手动补充 ETL 血缘关系 | 血缘写入 relationship，支持 DAG 可视化和影响分析 |
| **置信度** | 查询成功 +2、用户赞 +10、用户踩(审核通过) -15 | 管理员审核负面反馈 | 置信度变化影响 RAG 排序：高置信度字段优先召回 |
| **权限管理** | BLOCKED/DEPRECATED 自动阻断 | 管理员配置策略（ALLOW/DENY/MASK） | 策略实时生效，Python SQL AST 执行前过滤 |
| **敏感检测** | 基于标签自动识别 PII 字段 | 管理员确认治理状态 | SENSITIVE 状态字段自动进入脱敏流程 |

**核心原则**：AI 做重复性工作（推断、检测、评分），人做决策性工作（确认、审批、标记），系统做执行性工作（联动、过滤、脱敏）。

---

## 一、重构策略

### 1.1 核心原则

- **允许大改**：元数据治理是项目核心价值，浅尝辄止不如一步到位
- **清理先行**：重构完成后，**必须删除**被替代的旧代码，不留僵尸代码
- **Develop 集成隔离**：`main` 保持稳定可演示，`develop` 承接重构集成，阶段功能在独立 `feature/` 分支开发

### 1.2 Git 工作流

```
main
  └── develop                              ← 重构集成分支，阶段验收通过后再合入 main
      ├── feature/fix-permission-injection ← 阶段一：权限治理修复
      ├── feature/rag-restructure          ← 阶段二：RAG 重构
      ├── feature/metadata-entity-graph    ← 阶段三：实体关系图谱
      ├── feature/glossary-terms           ← 阶段四：业务术语表（可与阶段五并行）
      ├── feature/tag-classification       ← 阶段五：分类标签 + 质量深化（可与阶段四并行）
      ├── feature/permission-enhance       ← 阶段六：权限增强（依赖阶段五）
      └── feature/event-search             ← 阶段七：事件驱动
```

**分支规则**：
- `main`：稳定交付分支，只接收通过完整验收的 `develop` 合并
- `develop`：重构集成分支；首次创建时从 `main` 拉出，后续所有重构阶段先合入这里
- 从 `develop` 最新状态创建 feature 分支
- 分支命名：`feature/<简要描述>`（kebab-case）
- 阶段分支合并方式：feature → PR/Review → Squash Merge 到 `develop`
- 阶段合并前必须通过：`mvn test`（Java）+ `uv run pytest`（Python）+ 前端构建无报错；涉及联调时补充 Docker 端到端验证
- 阶段分支合并后立即删除 feature 分支
- 七个重构阶段全部完成并验收通过后，再由 `develop` 发起合并到 `main`
- `后续开发.md` 中的新功能任务不直接从当前阶段分支继续做；应在重构完成后，从当时最新 `develop` 或 `main` 按任务性质重新创建分支

**当前阶段一补救规则**：
- 如果阶段一已经误在 `main` 上产生未提交改动，先创建/切换到 `feature/fix-permission-injection` 承接这些改动
- Review 和测试通过后，将 `feature/fix-permission-injection` 合入 `develop`
- 如果 `develop` 尚不存在，先从当前稳定 `main` 创建 `develop`，再合入阶段一分支

### 1.3 代码清理原则

每个阶段完成后，必须清理被替代的旧代码：

- 删除前 grep 全项目确认无运行时引用
- 如果旧表仍有审计数据依赖，保留表结构但标记 `@Deprecated`
- Flyway 迁移不删除旧表（保留数据），只标记废弃
- 前端页面中引用旧 API 的地方，同步更新或删除
- 完成后更新 CLAUDE.md 反映新架构

---

## 二、全局执行顺序

```
阶段一：权限治理修复（feature/fix-permission-injection）── 稳定基础
  │
  ↓
阶段二：RAG 重构（feature/rag-restructure）── AI 能力基础
  │
  ↓
阶段三：实体关系图谱（feature/metadata-entity-graph）── 数据模型基础
  │
  ├──────────────────────┬──────────────────────┐
  ↓                      ↓                      │
阶段四：业务术语表        阶段五：分类标签 + 质量深化  │
（feature/glossary-terms）  （feature/tag-classification）│
  │                      │                      │
  └──────────┬───────────┘                      │
             ↓                                  │
        阶段六：权限增强（feature/permission-enhance）│
        （依赖阶段五的 PII 标签做自动脱敏）          │
             │                                  │
             ↓                                  │
        阶段七：事件驱动（feature/event-search）
  │
  ↓
重构完成 → 测试通过 → 本文档执行结束
```

**阶段依赖说明**：
- 阶段三完成后，阶段四和阶段五可以**并行**开发（互不依赖）
- 阶段六依赖阶段五（权限增强中的 PII 自动脱敏需要标签系统）

---

## 三、重构口径约束

### 3.1 权限入口口径

企业权限配置入口遵循：**部门 → 岗位/角色 → 员工例外**。

- 部门是企业组织归属，是最基础的授权维度
- 岗位/角色承载员工职责权限；当前系统只有 ROLE 时，先由 ROLE 承担"岗位/角色"语义
- 员工个人授权只作为特殊例外，不作为默认配置主路径
- 不强行新增职位表，除非后续重构阶段明确要求
- UI 配置入口优先选择部门，再选择岗位/角色，特殊情况才单独添加员工级策略
- 底层策略计算必须安全优先：系统级 DENY、BLOCKED、DEPRECATED 不得被员工例外绕过
- 敏感标签策略必须强制脱敏；显式 DENY 优先于普通 ALLOW
- 员工例外必须具备优先级、审计记录和冲突处理

### 3.2 后台侧栏口径

后台侧栏按业务链路组织，不按代码包名机械分组。

- 血缘属于数据资产治理链路，应靠近元数据、质量治理、字段治理，而不是孤立放在审计运维里
- 权限入口按组织结构优先：部门 → 岗位/角色 → 用户
- 当前没有实现、且属于后续开发的页面，不提前写进导航
- 后续开发项不进入本文档侧栏方案
- 当前阶段只有在确实涉及前端导航时，才调整侧栏

---

## 四、阶段一：权限治理修复 ✅ 已完成

**分支**：`feature/fix-permission-injection`
**预计工期**：1 周
**目标**：修复权限和治理模块的安全漏洞和逻辑缺陷
**完成日期**：2026-06-14

### 任务清单

#### 1.1 权限合并逻辑修正（P2-G1）

**问题**：`PermissionCalculatorImpl` 的多维度权限合并策略存在安全隐患

**现状**（经代码验证）：
- denied columns：多维度取**交集**（所有维度都 DENY 才禁止）→ 应为**并集**
- denied tables：多维度取**交集** → 应为**并集**
- allowed tables：多维度取**并集**，再减去 denied → 正确
- mask columns：多维度取**交集**（所有维度都 MASK 才脱敏）→ 应为**并集**
- row filters：多维度 AND 合并 → 正确
- canViewSql / canExport：多维度取**并集** → 正确

**修改**：
- 文件：`PermissionCalculatorImpl.java`
- denied columns/tables 改为**并集**（任一维度 DENY 即禁止）
- mask columns 改为**并集**（任一维度 MASK 即脱敏）
- 新增策略冲突日志（同时存在 ALLOW 和 DENY 时记录审计）

#### 1.2 访问策略 SQL 注入防御（P2-G2）

**问题**：`DatasourceAccessPolicy.rowFilterExpression` 直接拼入 SQL WHERE 子句

**修改**：
- 文件：`PermissionCalculatorImpl.java`
- 新增 `RowFilterSanitizer.sanitize()` 方法
- 黑名单：`--`, `;`, `UNION`, `DROP`, `DELETE`, `INSERT`, `UPDATE`, `EXEC`, `xp_`
- 转义单引号 `\'`，限制长度 500 字符
- 空值检查：null/blank 时返回 `1=1`

#### 1.3 权限计算器性能优化（P2-G3）

**问题**：`calculatePermission()` 对每个策略单独查库（N+1 查询）

**修改**：
- 文件：`PermissionCalculatorImpl.java`, `DatasourceAccessPolicyMapper.java`
- 新增 `selectByDatasourceId()` 批量查询方法
- 一次查出所有策略，在内存中按 subjectType 分组
- 使用 `@Cacheable(value="permission", key="#userId+':'+#datasourceId", ttl=5)`

#### 1.4 权限缓存事务隔离（P2-G4）

**问题**：缓存在事务提交前被清除，新事务可能读到脏数据

**修改**：
- 文件：`AccessPolicyController.java`
- `@CacheEvict` 改为 `@TransactionalEventListener(phase=AFTER_COMMIT)`
- 事务提交后才清除缓存

#### 1.5 治理 Issue 状态机修正（P2-G5）

**问题**：REOPENED 状态可直接跳到 RESOLVED，违反生命周期

**修改**：
- 文件：`QualityIssueService.java`
- 新增校验：`REOPENED → CONFIRMED → RESOLVED`（必须经过 CONFIRMED）

#### 1.6 冗余 Mapper 删除（P2-G6）~~已取消~~

> **2026-06-14 验证**：`DatasourceConfigMapper.java` 不存在于代码库中，`DatasourceMapper.java` 被 16 个类使用，提供 8 个自定义查询方法，不可删除。前端 `adminDatasource.ts` 中的 `getOverview`、`getHealth`、`refreshMetadata` 已在之前清理中移除。此任务取消。

#### 1.7 Facade 测试加固（P2-G7）

**新增文件**：
- `src/test/java/com/dataocean/facade/AdminFacadeTest.java`（MockMvc 集成测试）

### 代码清理（阶段一）

| 删除/修改 | 原因 | 状态 |
|-----------|------|------|
| `DatasourceMapper.java` | ~~冗余~~ 实际被 16 个类使用，不可删除 | ❌ 取消 |
| 前端 `getOverview`、`getHealth`、`refreshMetadata` | 404/未使用 | ✅ 已在之前清理 |

---

## 五、阶段二：RAG 重构 ✅ 已完成

**分支**：`feature/rag-restructure`
**预计工期**：2-3 周
**目标**：重构 RAG 架构，为术语表和深层治理打基础
**完成日期**：2026-06-14

### 任务清单

#### 2.1 RAG 统一入口重构

**问题**：RAG 检索、向量化、Milvus 操作分散在多个服务中

**修改**：
- 统一 `Retriever` 为 RAG 唯一入口
- `VectorStore` 只负责 Milvus 读写
- `Vectorizer` 只负责 embedding 调用
- 检索结果增加 chunk 类型权重（JOIN_PATH、METRIC、FIELD_NOTE、QUERY_SCENE）

#### 2.2 Embedding 初始化竞态修复

**问题**：多线程同时初始化 embedding 模型

**修改**：
- 使用 `asyncio.Lock` + double-check 模式
- 文件：`python-service/dataocean/infra/embedding.py`

#### 2.3 向量化 force 模式安全修复

**问题**：force 模式直接删除旧向量再写入新向量，中间窗口数据丢失

**修改**：
- 改为 staging 写入 → 验证 → 替换的三步模式
- 文件：`python-service/dataocean/rag/vector_store.py`

#### 2.4 SSE 解析完善

**问题**：SSE 事件解析在边界情况下可能丢失数据

**修改**：
- 完善 buffer 处理和断线重连逻辑
- 文件：`python-service/dataocean/infra/sse.py`

#### 2.5 LLM 初始化竞态修复

**问题**：配置热重载时 LLM 实例可能被部分初始化

**修改**：
- 使用 `threading.Lock` 保护 LLM 实例创建
- 文件：`python-service/dataocean/infra/llm.py`

### 代码清理（阶段二）

| 删除/修改 | 原因 | 状态 |
|-----------|------|------|
| 重复的 embedding 初始化代码 | 统一到 InfraEmbedding | ✅ 已确认无重复代码 |
| 硬编码的 RAG 阈值 | 改为配置项 | ✅ 已确认使用 settings.similarity_threshold |

---

## 六、阶段三：实体关系图谱 ✅ 已完成

**分支**：`feature/metadata-entity-graph`
**预计工期**：2-3 周
**目标**：建立统一实体-关系模型，替代当前孤立的表/列存储
**完成日期**：2026-06-14

### 任务清单

#### 3.1 新建 Flyway 迁移 `V37__metadata_entity_graph.sql`

```sql
-- 统一实体注册表
CREATE TABLE metadata_entity (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    entity_type     VARCHAR(32) NOT NULL,       -- DATASOURCE, DATABASE, TABLE, COLUMN, GLOSSARY_TERM, TAG
    entity_uuid     CHAR(36) NOT NULL UNIQUE,
    fqn             VARCHAR(512) NOT NULL,        -- 全限定名: datasource.db.table.column
    name            VARCHAR(256) NOT NULL,
    display_name    VARCHAR(256),
    description     TEXT,
    entity_metadata JSON,
    owner_id        BIGINT,
    version         INT NOT NULL DEFAULT 1,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_entity_type (entity_type),
    INDEX idx_entity_fqn (fqn),
    INDEX idx_entity_uuid (entity_uuid),
    FULLTEXT INDEX ft_entity_search (name, display_name, description)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 统一关系边表
CREATE TABLE metadata_relationship (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_id       BIGINT NOT NULL,
    source_type     VARCHAR(32) NOT NULL,
    target_id       BIGINT NOT NULL,
    target_type     VARCHAR(32) NOT NULL,
    relation_type   VARCHAR(32) NOT NULL,          -- CONTAINS, HAS_PART, LINEAGE, TAGGED_WITH, GLOSSARY_OF, FOREIGN_KEY
    relation_metadata JSON,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_relationship (source_id, target_id, relation_type),
    INDEX idx_rel_source (source_id, relation_type),
    INDEX idx_rel_target (target_id, relation_type),
    INDEX idx_rel_type (relation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**关系类型**：

| relation_type | 含义 | 示例 |
|---------------|------|------|
| `CONTAINS` | 包含 | 数据源 CONTAINS 表 |
| `HAS_PART` | 组成 | 表 HAS_PART 列 |
| `LINEAGE` | 血缘 | 表A LINEAGE 表B |
| `TAGGED_WITH` | 标签 | 列 TAGGED_WITH 标签 |
| `GLOSSARY_OF` | 术语 | 列 GLOSSARY_OF 术语 |
| `FOREIGN_KEY` | 外键 | 列A FOREIGN_KEY 列B |
| `DERIVED_FROM` | 派生 | 指标 DERIVED_FROM 原始列 |
| `RELATED_TO` | 相关 | 术语 RELATED_TO 术语 |

#### 3.2 Java 实体和服务

**新增**：
- `metadata/entity/MetadataEntity.java`
- `metadata/entity/MetadataRelationship.java`
- `metadata/mapper/MetadataEntityMapper.java`
- `metadata/mapper/MetadataRelationshipMapper.java`
- `metadata/service/MetadataEntityService.java`
- `metadata/service/MetadataRelationshipService.java`

#### 3.3 快照发布时写入 Entity

修改 `SnapshotPublishService.publishSnapshot()`：

```
1. 从 snapshot 加载所有 DbTableMeta + DbColumnMeta
2. 为每张表生成 metadata_entity（type=TABLE, fqn=datasource.db.table）
3. 为每列生成 metadata_entity（type=COLUMN, fqn=datasource.db.table.column）
4. 建立 CONTAINS / HAS_PART 关系
5. 从 TableRelation 迁入 FOREIGN_KEY 关系
6. 删除该数据源旧的 entity 数据（发布新版本替代旧版本）
```

#### 3.4 FQN 体系

```
数据源：mysql_prod
表：    mysql_prod.mydb.orders
列：    mysql_prod.mydb.orders.customer_id
术语：  glossary.销售.订单金额
标签：  tag.PII.敏感
```

#### 3.5 元数据目录搜索 API

```
GET /api/admin/catalog/search
  ?q=订单金额          -- 全文搜索
  &type=COLUMN         -- 实体类型过滤
  &datasource_id=1     -- 数据源过滤
  &page=1&size=20
```

#### 3.6 血缘类型设计

血缘关系存储在 `metadata_relationship` 表中，通过 `relation_metadata` 区分三种血缘类型：

| lineage_type | 来源 | 含义 | 示例 |
|-------------|------|------|------|
| `QUERY` | sqlglot 自动提取 | NL2SQL 查询引用了哪些表/列 | 用户问"订单总额"→ SQL 引用了 orders.total_amount |
| `ETL` | 管理员手动创建 | 数据流转关系（表 A 产出表 B） | daily_stats 由 orders 聚合产出 |
| `MANUAL` | 管理员手动创建 | 业务层面的血缘补充 | 报表字段来源于多个计算指标 |

**当前 `query_lineage_table` / `query_lineage_column` 的定位**：
- 这是**审计数据**，记录"某次查询引用了哪些表/列"
- 不是"表 A 产出数据给表 B"的流转关系
- 迁移后保留原表用于审计，新的血缘关系写入 `metadata_relationship`

**relation_metadata 结构**（LINEAGE 类型）：

```json
{
  "lineage_type": "QUERY",
  "query_task_id": 12345,
  "sql_query": "SELECT total_amount FROM orders WHERE ...",
  "column_mappings": [
    {"from": ["orders.total_amount"], "to": null, "expression": "SUM(amount)"}
  ]
}
```

```json
{
  "lineage_type": "ETL",
  "etl_job_name": "daily_stats_aggregation",
  "schedule": "0 2 * * *",
  "column_mappings": [
    {"from": ["orders.total_amount"], "to": "daily_stats.revenue"], "expression": "SUM"}
  ]
}
```

#### 3.7 血缘 DAG 可视化

**新增前端页面**：`frontend/src/views/admin/audit/LineageGraph.vue`

使用 ECharts Graph（已在项目中）：
- 表级血缘 DAG（节点=表，边=数据流转）
- 点击表节点展开列级血缘
- 上游追溯 / 下游影响
- 按数据源过滤
- 按血缘类型着色：QUERY（蓝色）、ETL（绿色）、MANUAL（灰色）

#### 3.8 血缘影响分析增强

```sql
-- 递归 CTE 查找所有下游依赖
WITH RECURSIVE downstream AS (
    SELECT target_id, 1 as depth
    FROM metadata_relationship
    WHERE source_id = ? AND relation_type = 'LINEAGE'
    UNION ALL
    SELECT r.target_id, d.depth + 1
    FROM metadata_relationship r
    JOIN downstream d ON r.source_id = d.target_id
    WHERE r.relation_type = 'LINEAGE' AND d.depth < 10
)
SELECT DISTINCT e.*, d.depth
FROM downstream d
JOIN metadata_entity e ON e.id = d.target_id
ORDER BY d.depth;
```

### 代码清理（阶段三）

| 删除/废弃 | 替代 |
|-----------|------|
| `TableRelation` 实体标记 `@Deprecated` | `metadata_relationship` |
| `RelationCollector` 保留（快照采集用），关系同步写入新表 | — |
| `LineageViewer.vue` 纯表格展示 | DAG + 表格双视图 |

---

## 七、阶段四：业务术语表 ✅ 已完成

**分支**：`feature/glossary-terms`
**预计工期**：2-3 周
**目标**：建立业务术语体系，让 NL2SQL 理解用户语言
**完成日期**：2026-06-14

### 任务清单

#### 4.1 新建 Flyway 迁移 `V38__glossary_tables.sql`

```sql
CREATE TABLE glossary (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    name            VARCHAR(128) NOT NULL UNIQUE,
    display_name    VARCHAR(256),
    description     TEXT,
    owner_id        BIGINT,
    status          VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (owner_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE glossary_term (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    glossary_id     BIGINT NOT NULL,
    parent_id       BIGINT,
    name            VARCHAR(128) NOT NULL,
    display_name    VARCHAR(256),
    description     TEXT,
    synonyms        JSON,                          -- ["营收", "收入", "Revenue"]
    related_terms   JSON,
    fqn             VARCHAR(512) NOT NULL UNIQUE,
    status          VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    reviewer_id     BIGINT,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (glossary_id) REFERENCES glossary(id),
    INDEX idx_term_glossary (glossary_id),
    INDEX idx_term_parent (parent_id),
    INDEX idx_term_fqn (fqn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### 4.2 Java 模块

**新增** `backend/DataOcean/src/main/java/com/dataocean/module/glossary/`：
- 实体：`Glossary`, `GlossaryTerm`
- Mapper：`GlossaryMapper`, `GlossaryTermMapper`
- Service：`GlossaryService`, `GlossaryTermService`, `GlossaryApprovalService`
- Controller：`GlossaryController` at `/api/admin/glossary`

#### 4.3 前端页面

**新增** `frontend/src/views/admin/glossary/`：
- `GlossaryList.vue` — 术语表列表
- `TermEditor.vue` — 术语编辑（含同义词、关联列选择）
- `TermApproval.vue` — 术语审批

#### 4.4 RAG 集成

Python 服务查询改写阶段增加术语匹配：

```python
# python-service/dataocean/agent/nodes/query_rewriter.py
async def rewrite_with_glossary(self, question: str, datasource_id: int) -> str:
    """用术语同义词扩展用户问题，提高 RAG 召回率"""
    # 1. 从 Java 获取该数据源的术语表
    # 2. 匹配用户问题中的术语
    # 3. 用术语对应的物理列名扩展问题
```

**价值**：用户说"订单金额"→ 系统知道对应 `orders.total_amount` 列

#### 4.5 术语与列的关联

通过 `metadata_relationship` 表：

```
source: glossary_term (订单金额)
target: metadata_entity (orders.total_amount)
relation_type: GLOSSARY_OF
```

---

## 八、阶段五：分类标签与质量深化 ✅ 已完成

**分支**：`feature/tag-classification`
**预计工期**：2 周
**目标**：建立两级标签体系，深化数据质量规则
**完成日期**：2026-06-14

### 任务清单

#### 5.1 新建 Flyway 迁移 `V39__classification_tag_tables.sql`

```sql
CREATE TABLE classification (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    name            VARCHAR(64) NOT NULL UNIQUE,
    description     VARCHAR(512),
    is_system       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tag (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    classification_id BIGINT NOT NULL,
    name            VARCHAR(64) NOT NULL,
    display_name    VARCHAR(128),
    description     VARCHAR(512),
    fqn             VARCHAR(256) NOT NULL UNIQUE,
    style           JSON,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (classification_id) REFERENCES classification(id),
    UNIQUE KEY uk_tag (classification_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**预置数据**：

| 分类 | 标签 |
|------|------|
| PII | 身份证号、手机号、姓名、邮箱、银行卡号、地址 |
| 数据分级 | 公开、内部、机密、绝密 |
| 业务域 | 销售、财务、人力、供应链 |

#### 5.2 标签自动推断

```python
# python-service/dataocean/infra/auto_tagger.py
class AutoTagger:
    PATTERNS = {
        'PII.手机号': [r'phone', r'手机', r'电话', r'mobile'],
        'PII.身份证号': [r'id_card', r'身份证', r'identity'],
        'PII.邮箱': [r'email', r'邮箱'],
        '业务域.财务': [r'amount', r'金额', r'price', r'cost'],
    }
```

#### 5.3 数据质量深化

**扩展 `metadata_quality_rule` 表**：

```sql
ALTER TABLE metadata_quality_rule
    ADD COLUMN check_type VARCHAR(32) NOT NULL DEFAULT 'SCHEMA' AFTER check_target,
    ADD COLUMN check_expression TEXT AFTER check_type,
    ADD COLUMN threshold DECIMAL(10,4) AFTER check_expression;
```

**新增数据级规则**：

| 规则代码 | 维度 | 检查内容 |
|----------|------|----------|
| `DATA_NULL_RATE_HIGH` | COMPLETENESS | 列空值率 > 30% |
| `DATA_UNIQUE_VIOLATION` | ACCURACY | 应唯一列出现重复值 |
| `DATA_FK_ORPHAN` | CONSISTENCY | 外键引用目标不存在 |
| `DATA_STALE_TABLE` | TIMELINESS | 表数据超过 N 天未更新 |

**质量趋势时序表**：

```sql
CREATE TABLE quality_check_result (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id     BIGINT NOT NULL,
    rule_id         BIGINT NOT NULL,
    dimension       VARCHAR(32) NOT NULL,
    score           DECIMAL(5,2) NOT NULL,
    issue_count     INT NOT NULL DEFAULT 0,
    checked_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_qcr_snapshot (snapshot_id),
    INDEX idx_qcr_time (checked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**前端**：`QualityDashboard.vue` 增加质量趋势折线图

### 代码清理（阶段五）

| 删除/废弃 | 替代 |
|-----------|------|
| `PredefinedTag` 实体标记 `@Deprecated` | `classification` + `tag` |
| `field_tag` 表保留（历史数据），新标签写入 `metadata_relationship` | — |

---

## 九、阶段六：权限增强

**分支**：`feature/permission-enhance`
**预计工期**：1-2 周
**目标**：增强现有权限模型，添加策略优先级、时间条件、权限变更审计

### 任务清单

#### 6.1 策略优先级

```sql
ALTER TABLE datasource_access_policy
    ADD COLUMN priority INT NOT NULL DEFAULT 100;
```

优先级越低越优先（0=最高）：系统级 0-99，管理员 100-199，默认 200+

**评估算法**：
1. 收集所有匹配策略（按 subject + 时间条件过滤）
2. 按 priority 升序排序
3. 高优先级 DENY → 立即生效（短路）
4. 高优先级 ALLOW → 立即生效（短路）
5. 无显式匹配 → 默认策略

#### 6.2 时间条件

```sql
ALTER TABLE datasource_access_policy
    ADD COLUMN valid_from DATETIME,
    ADD COLUMN valid_until DATETIME,
    ADD COLUMN time_schedule JSON;
-- {"weekdays": [1,2,3,4,5], "hours": {"from": "09:00", "to": "18:00"}}
```

#### 6.3 权限变更审计

```sql
CREATE TABLE permission_change_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    change_type     VARCHAR(32) NOT NULL,
    target_type     VARCHAR(16) NOT NULL,
    target_id       BIGINT NOT NULL,
    subject_type    VARCHAR(16),
    subject_id      BIGINT,
    datasource_id   BIGINT,
    old_value       JSON,
    new_value       JSON,
    operator_id     BIGINT NOT NULL,
    reason          VARCHAR(512),
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_pcl_type (change_type),
    INDEX idx_pcl_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 代码清理（阶段六）

| 删除/废弃 | 替代 |
|-----------|------|
| `PermissionCalculatorImpl` 硬编码的交集/并集逻辑 | 基于优先级的策略评估引擎 |

---

## 十、阶段七：事件驱动

**分支**：`feature/event-search`
**预计工期**：2 周
**目标**：元数据变更事件传播、数据访问审批

### 任务清单

#### 7.1 元数据变更事件

```sql
CREATE TABLE metadata_change_event (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_type      VARCHAR(32) NOT NULL,
    entity_type     VARCHAR(32) NOT NULL,
    entity_id       BIGINT NOT NULL,
    entity_fqn      VARCHAR(512) NOT NULL,
    change_data     JSON,
    operator_id     BIGINT,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_mce_type (event_type),
    INDEX idx_mce_entity (entity_type, entity_id),
    INDEX idx_mce_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### 7.2 数据访问审批

```sql
CREATE TABLE access_approval_request (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    requester_id    BIGINT NOT NULL,
    datasource_id   BIGINT NOT NULL,
    table_name      VARCHAR(128) NOT NULL,
    column_name     VARCHAR(128),
    request_reason  TEXT NOT NULL,
    requested_duration INT,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    approver_id     BIGINT,
    approved_at     DATETIME,
    expires_at      DATETIME,
    reject_reason   TEXT,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_aar_status (status),
    INDEX idx_aar_requester (requester_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**流程**：用户查询命中 MASK → 显示脱敏结果 + "申请查看"按钮 → 填写理由 → 管理员审批 → 通过生成临时 ALLOW 策略

---

## 十一、当前代码关键事实（经代码验证）

执行前了解这些事实，避免踩坑：

### 权限合并逻辑（PermissionCalculatorImpl）✅ 已修正

| 维度 | 修正前 | 修正后（2026-06-14） |
|------|--------|---------------------|
| denied columns | 交集（所有维度都 DENY 才禁止） | **并集**（任一维度 DENY 即禁止） |
| denied tables | 交集 | **并集** |
| allowed tables | 并集 - denied | 并集 - denied（不变） |
| mask columns | 交集 | **并集**（任一维度 MASK 即脱敏） |
| row filters | AND 合并 | AND 合并（不变） |
| canViewSql/canExport | 并集 | 并集（不变） |
| 缓存失效 | 事务内直接清除 | **事务提交后清除**（@TransactionalEventListener） |
| 策略加载 | N+1 逐主体查询 | **批量查询**（selectAllByDatasourceId） |
| 表范围协议 | Java 未传递 tableScopeMode | **补齐 tableScopeMode**（UNRESTRICTED/ALLOWLIST），修正 `*` 表策略语义 |

### 治理质量规则（11 条，V11 迁移种子）

| 规则代码 | 维度 | 严重性 | 扣分 |
|----------|------|--------|------|
| COMP_TABLE_COMMENT_MISSING | COMPLETENESS (30%) | HIGH | -5 |
| COMP_COLUMN_COMMENT_MISSING | COMPLETENESS | MEDIUM | -2 |
| COMP_PRIMARY_KEY_MISSING | COMPLETENESS | HIGH | -8 |
| ACCU_TYPE_NAME_MISMATCH | ACCURACY (25%) | MEDIUM | -3 |
| ACCU_ENUM_ANOMALY | ACCURACY | LOW | -1.5 |
| CONS_CROSS_TABLE_TYPE_MISMATCH | CONSISTENCY (25%) | HIGH | -5 |
| CONS_CROSS_TABLE_COMMENT_CONFLICT | CONSISTENCY | LOW | -1 |
| TIME_SNAPSHOT_EXPIRED | TIMELINESS (10%) | MEDIUM | -3 |
| TIME_TABLE_NO_UPDATE | TIMELINESS | LOW | -2 |
| TRACE_FK_MISSING | TRACEABILITY (10%) | MEDIUM | -3 |
| TRACE_ISOLATED_TABLE | TRACEABILITY | LOW | -2 |

### PredefinedTag（8 个，V17 迁移种子）

| tag_code | tag_name | category |
|----------|----------|----------|
| AMOUNT | 金额类 | 业务类型 |
| TIME | 时间类 | 业务类型 |
| STATUS | 状态类 | 业务类型 |
| USER_ID | 用户ID类 | 业务类型 |
| SENSITIVE | 敏感 | 安全标记 |
| DEPRECATED | 废弃 | 生命周期 |
| RECOMMENDED | 推荐 | 生命周期 |
| BLOCKED | 阻断 | 生命周期 |

### 血缘表（V20 迁移）

- `query_lineage_table`：查询时引用记录（FROM/JOIN/SUBQUERY），关联 query_task_id
- `query_lineage_column`：查询时列引用记录（含表达式和别名）
- **注意**：这是审计数据，不是"表 A 产出数据给表 B"的流转关系

### TableRelation（V7 迁移）

- 快照作用域（snapshot_id），每次同步重建
- 列级粒度：source_table.source_column → target_table.target_column
- 三种类型：FK(外键)、INFERRED(命名推断)、MANUAL(手动标注)
- **MANUAL 记录会在重新同步时丢失**

### AlertRule（V21 迁移）

- 仅 7 列：metric、threshold、operator、notification_type、enabled
- 缺少：name、description、severity、cooldown、webhook_url、event_types

---

## 十二、Flyway 迁移编号汇总

| 编号 | 内容 | 对应阶段 |
|------|------|----------|
| V37 | metadata_entity + metadata_relationship | 阶段三 |
| V38 | glossary + glossary_term | 阶段四 |
| V39 | classification + tag + 种子数据 | 阶段五 |
| V40 | quality_rule 扩展 + quality_check_result | 阶段五 |
| V41 | permission 增强（priority、time、changelog） | 阶段六 |
| V42 | metadata_change_event + access_approval_request | 阶段七 |

---

## 十三、前端新增页面汇总

| 页面 | 路径 | 对应阶段 | 重构优先级 |
|------|------|----------|--------|
| 元数据目录搜索 | `/admin/catalog/search` | 阶段三 | 高 |
| 血缘 DAG 图 | `/admin/audit/lineage-graph` | 阶段三 | 高 |
| 业务术语管理 | `/admin/glossary/list` | 阶段四 | 高 |
| 术语审批 | `/admin/glossary/approval` | 阶段四 | 高 |
| 标签分类管理 | `/admin/tags/classifications` | 阶段五 | 中 |
| 质量趋势仪表盘 | `/admin/governance/trends` | 阶段五 | 中 |
| 权限变更日志 | `/admin/permission/changelog` | 阶段六 | 低 |
| 数据访问审批 | `/admin/permission/approvals` | 阶段七 | 低 |

---

*本方案参考 OpenMetadata（https://github.com/open-metadata/OpenMetadata）的成熟设计，结合 DataOcean 的 NL2SQL 特色进行定制。*
