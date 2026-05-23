<p align="center">
  <img src="docs/images/dataocean-banner.png" alt="DataOcean Banner" width="100%"/>
</p>

<h1 align="center">DataOcean</h1>

<p align="center">
  <strong>企业级 NL2SQL 智能数据查询与治理平台</strong><br/>
  用自然语言查询数据库，AI 自动生成 SQL 并返回表格与图表结果
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-orange?style=flat-square" alt="Java 17"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.x-green?style=flat-square" alt="Spring Boot"/>
  <img src="https://img.shields.io/badge/Vue-3-blue?style=flat-square" alt="Vue 3"/>
  <img src="https://img.shields.io/badge/Python-3.13-yellow?style=flat-square" alt="Python"/>
  <img src="https://img.shields.io/badge/License-MIT-purple?style=flat-square" alt="MIT"/>
</p>

---

## 这个项目是什么

DataOcean 是一个面向企业的智能数据查询平台。业务人员不需要懂 SQL，只需要用中文描述想查什么数据，系统就能自动理解意图、生成 SQL、执行查询，并以表格或图表的形式返回结果。

与市面上的 Text-to-SQL 工具不同，DataOcean 的核心理念是**治理驱动的可信查询**——AI 生成 SQL 所依赖的每一条元数据，都必须经过采集、质量校验、人工审核、发布的完整治理流程，确保查询结果可信、可追溯。

---

## 能解决什么问题

| 痛点 | DataOcean 的解决方案 |
|------|---------------------|
| 业务人员不会写 SQL，每次都要找开发 | 自然语言直接查询，AI 自动生成 SQL |
| 数据库表太多，AI 不知道该查哪张表 | Schema RAG 向量检索，精准召回 Top 5-10 相关表 |
| AI 生成的 SQL 不可信，可能查错数据 | 元数据治理闭环，所有 AI 依据都经过人工审核 |
| 担心 SQL 注入或越权查询 | AST 安全校验 + 只读账号 + 行列级权限，多层防护 |
| 不同业务对同一字段理解不同 | skills.md 业务知识库，统一业务语义定义 |
| 字段质量参差不齐，AI 选错字段 | 字段可信度评分（0-100），动态影响字段选择优先级 |

---

## 系统架构

<p align="center">
  <img src="docs/images/dataocean-architecture.png" alt="系统架构" width="80%"/>
</p>

系统采用三层分离架构，前端统一调用 Java 网关，Java 内部代理调用 Python AI 服务：

```
┌─────────────────────────────────────────────────────────────┐
│  Vue 3 前端（问答端 /query + 治理管理端 /admin）              │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTP API
┌──────────────────────────▼──────────────────────────────────┐
│  Java 网关层（Spring Boot 3.x）                              │
│  鉴权 · 权限 · 数据源管理 · 元数据治理 · 审计                  │
└──────────────────────────┬──────────────────────────────────┘
                           │ 内部 HTTP（OpenFeign → FastAPI）
┌──────────────────────────▼──────────────────────────────────┐
│  Python AI 服务（FastAPI + LangGraph + LlamaIndex）           │
│  Query Rewrite · Schema RAG · SQL 生成 · 安全校验 · 执行      │
└──────────────────────────┬──────────────────────────────────┘
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
      Milvus 2.x      MySQL 8          Qwen API
      (向量库)         (业务库+管理库)    (大模型)
```

---

## 核心流程

<p align="center">
  <img src="docs/images/dataocean-core-flow.png" alt="查询流程" width="85%"/>
</p>

一次完整的自然语言查询经过以下步骤：

1. **Query Rewrite** — 时间解析、指代消解、意图提取，将模糊问题改写为结构化查询
2. **Schema RAG** — 向量检索召回最相关的 5-10 张表的元数据
3. **SQL Generation** — 大模型基于召回的 schema + skills.md 生成 SQL
4. **Security Check** — sqlglot AST 校验（仅允许 SELECT，禁止危险函数，强制 LIMIT）
5. **Execution** — 只读连接执行 SQL，超时 30s 保护
6. **Visualization** — 根据数据特征自动生成 ECharts 图表配置

---

## 治理闭环

<p align="center">
  <img src="docs/images/dataocean-governance-cycle.png" alt="治理闭环" width="60%"/>
</p>

DataOcean 的核心竞争力在于**治理驱动**。AI 不是直接读取原始数据库 schema，而是依赖经过完整治理流程的元数据：

```
采集 → 质量校验 → 问题修正 → 审核发布快照 → 生成 skills.md → 向量化 → 查询引用 → 反馈回流
```

只有状态为 PUBLISHED 的快照才能进入 RAG，确保 AI 依据的准确性。

---

## 安全机制

<p align="center">
  <img src="docs/images/dataocean-security-shield.png" alt="安全防护" width="50%"/>
</p>

| 防护层 | 措施 |
|--------|------|
| 连接层 | 业务库强制只读账号，密码 AES-256 加密存储 |
| SQL 层 | AST 校验仅允许 SELECT，禁止 DROP/DELETE/UPDATE 等 |
| 执行层 | 强制 LIMIT 10000，超时 30s，子查询最大 3 层 |
| 权限层 | 行列级权限在 SQL AST 层强制注入，不依赖 Prompt |
| 脱敏层 | SENSITIVE 字段可查但自动脱敏，BLOCKED 字段禁止召回 |
| 认证层 | JWT + Redis 黑名单，退出即失效 |

---

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + Vite + TypeScript + Element Plus + ECharts + Pinia |
| Java 网关 | Spring Boot 3.x + Spring Security + JWT + MyBatis-Plus + Flyway |
| Python AI | Python 3.13 + FastAPI + LangGraph + LlamaIndex + sqlglot |
| 向量库 | Milvus 2.x Standalone |
| 数据库 | MySQL 8（管理库 + 业务库） |
| 缓存 | Redis（JWT 黑名单、限流、任务状态） |
| 大模型 | 通义千问 Qwen API |
| Embedding | text-embedding-v4（1024 维） |
| 部署 | Docker Compose |

---

## 项目结构

```
DataOcean/
├── frontend/              Vue 3 前端项目
│   ├── src/views/query/   问答端页面
│   └── src/views/admin/   治理管理端页面
├── backend/               Spring Boot Java 网关
│   └── DataOcean/src/     主工程源码
├── python-service/        Python AI 服务
│   └── dataocean/         Agent 工作流
├── docs/                  设计文档与图片
├── specs/                 17 个模块的规格说明
└── docker-compose.yml     基础设施编排
```

---

## 新手使用教程

> 以下教程面向首次使用 DataOcean 的用户，从登录到完成第一次查询的完整流程。

### 一、登录系统

打开浏览器访问平台地址，输入管理员分配的账号密码登录。首次登录系统会要求修改初始密码。

登录后根据你的角色进入不同界面：
- **管理员/治理人员** → 自动进入后台管理工作台
- **普通业务用户** → 自动进入智能查询页面

---

### 二、管理员：接入数据源

> 这一步由管理员完成，普通用户跳过。

1. 进入侧边栏「数据源管理」
2. 点击「新增数据源」，填写数据库连接信息（主机、端口、库名、只读账号）
3. 点击「测试连接」确认连通后保存
4. 在数据源列表中点击「授权」，选择允许查询该数据源的用户

---

### 三、管理员：元数据治理

> 治理是 DataOcean 的核心环节，确保 AI 查询依据可信。

**采集元数据：**
1. 进入「同步任务」，选择数据源，点击「触发同步」
2. 系统自动采集该库所有表结构、字段信息，生成快照

**质量校验：**
1. 进入「质量看板」，选择刚采集的快照
2. 点击「执行质量校验」，系统自动检测命名规范、注释完整性等
3. 查看质量分和问题列表

**处理问题：**
1. 进入「问题清单」，查看校验发现的问题
2. 对每个问题进行处理（确认、解决、驳回）
3. 高风险问题必须全部处理后才能发布

**审核发布：**
1. 进入「快照生命周期」，选择数据源
2. 对 DRAFT 状态的快照点击「开始校验」
3. 校验通过后点击「发布」
4. 发布后该快照成为 AI 查询的唯一依据

---

### 四、业务用户：自然语言查询

1. 进入「智能查询」页面
2. 从下拉列表选择一个已授权的数据源
3. 在输入框用中文描述你想查的数据，例如：
   - "上个月销售额最高的 10 个产品"
   - "各部门本季度的人员变动情况"
   - "最近 7 天每天的订单量趋势"
4. 系统自动完成：理解意图 → 召回相关表 → 生成 SQL → 安全校验 → 执行查询
5. 结果以表格形式展示，适合可视化的数据会自动生成图表

---

### 五、版本管理与紧急撤回

当发现已发布的元数据有问题时：

1. 进入「快照生命周期」
2. 找到当前 PUBLISHED 状态的快照，点击「撤回」
3. 填写撤回原因后确认
4. 系统立即停止使用该快照，AI 查询将暂停直到新版本发布

---

### 六、查看操作历史

- 「版本历史」页面可以查看某个数据源所有快照的演变时间线
- 支持选择两个版本进行对比，查看表和字段的增删改变化
- 每个快照的操作日志记录了谁在什么时间做了什么操作

---

## 模块总览

| # | 模块 | 说明 | 状态 |
|---|------|------|------|
| 001 | 用户模块 | 登录、角色、部门、权限 | 已完成 |
| 002 | 数据源管理 | 连接配置、健康检测、授权 | 已完成 |
| 003 | 元数据采集 | Schema 同步、快照、变更检测 | 已完成 |
| 004 | 元数据治理 | 五维质量校验、问题管理 | 已完成 |
| 005 | 版本与审核 | 快照生命周期、发布、撤回 | 已完成 |
| 006 | skills.md | 业务知识库生成与管理 | 已完成 |
| 007 | Schema RAG | 向量检索召回相关表 | 已完成 |
| 008 | NL2SQL Agent | LangGraph 查询工作流 | 规划中 |
| 009 | SQL 安全沙箱 | AST 校验 + 权限注入 | 规划中 |
| 010 | 字段可信度 | 动态评分与反馈调整 | 规划中 |
| 011 | 血缘与审计 | 查询血缘、操作审计 | 规划中 |
| 012 | 前端问答端 | 自然语言查询界面 | 规划中 |
| 013 | 后台管理端 | 治理管理界面 | 持续迭代 |
| 014 | Prompt 管理 | 模板化 Prompt 配置 | 规划中 |
| 015 | 权限与安全 | 行列级权限 CRUD | 规划中 |
| 016 | 图表生成 | ECharts 可视化 | 规划中 |
| 017 | 错误与降级 | 异常处理、优雅降级 | 规划中 |

---

## License

MIT
