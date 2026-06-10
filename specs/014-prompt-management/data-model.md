# Data Model: Prompt 管理模块

## Entity Relationship

```
prompt_template (1) ──< prompt_template_version (N)
```

## Tables

### prompt_template

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 模板ID |
| template_code | VARCHAR(50) | UNIQUE, NOT NULL | 模板编码 (sql_generation, chart_generation 等) |
| template_name | VARCHAR(100) | NOT NULL | 模板名称 |
| scenario | VARCHAR(50) | NOT NULL | 使用场景 (query/chart/intent/retrieval/memory) |
| content | TEXT | NOT NULL | 当前活跃版本的模板内容（冗余，加速读取） |
| current_version | INT | NOT NULL, DEFAULT 1 | 当前活跃版本号 |
| status | VARCHAR(20) | NOT NULL, DEFAULT 'APPROVED' | 当前流程状态：DRAFT/PENDING_REVIEW/APPROVED/REJECTED |
| enabled | TINYINT | NOT NULL, DEFAULT 1 | 1=启用, 0=禁用 |
| version | INT | NOT NULL, DEFAULT 0 | 乐观锁版本 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |
| updated_at | DATETIME | NOT NULL, DEFAULT NOW() ON UPDATE | 更新时间 |

> 说明：`prompt_template.content/current_version` 始终表示 Python 运行时读取的线上发布版本。编辑、提交审核和拒绝不会覆盖该内容；只有审核通过才更新。

### prompt_template_version

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 版本ID |
| template_id | BIGINT | FK → prompt_template.id, NOT NULL | 所属模板 |
| version_no | INT | NOT NULL | 版本号（模板内递增） |
| content | TEXT | NOT NULL | 该版本的模板内容 |
| change_summary | VARCHAR(500) | | 修改摘要 |
| is_active | TINYINT | NOT NULL, DEFAULT 0 | 是否为当前线上发布版本 |
| status | VARCHAR(20) | NOT NULL, DEFAULT 'APPROVED' | 版本状态：DRAFT/PENDING_REVIEW/APPROVED/REJECTED |
| created_by | BIGINT | FK → sys_user.id | 修改人 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |

UNIQUE INDEX: (template_id, version_no)

INDEX: (template_id, status, version_no)

## Approval Workflow

| Action | Template Status | Version Status | Runtime Effect |
|--------|-----------------|----------------|----------------|
| 保存模板 | DRAFT | 新版本 DRAFT, is_active=0 | Python 继续读取旧发布版本 |
| 提交审核 | PENDING_REVIEW | 最新草稿变为 PENDING_REVIEW | Python 继续读取旧发布版本 |
| 审核通过 | APPROVED | 待审核版本变为 APPROVED + is_active=1，旧 active 版本置为 0 | Python 开始读取新发布版本 |
| 审核拒绝 | REJECTED | 待审核版本变为 REJECTED | Python 继续读取旧发布版本 |
| 回滚 | DRAFT | 基于目标版本创建新的 DRAFT | Python 继续读取旧发布版本，直到回滚草稿审核通过 |

## Initial Data (Flyway V10)

5 个核心模板初始记录：

| template_code | template_name | scenario |
|---------------|---------------|----------|
| query_rewrite | 问题理解与改写 | rewrite |
| sql_generation | SQL 生成 | query |
| chart_generation | 图表生成 | chart |
| intent_recognition | 意图识别（简单/复杂判断） | intent |
| schema_retrieval_query | Schema 检索查询改写 | retrieval |
| memory_extraction | 记忆提取 | memory |

## Template Variables

各模板支持的变量：

| Variable | Description | Token Priority | Budget |
|----------|-------------|----------------|--------|
| {{question}} | 用户原始问题 | - | 不限（必填） |
| {{schema}} | 召回的表结构 DDL | 1 (最高) | 1500 |
| {{skills_md}} | 相关 skills.md 片段 | 2 | 1000 |
| {{few_shot_templates}} | 相似问题的 SQL 示例 | 3 | 800 |
| {{conversationHistory}} | 多轮对话上下文 | 4 | 500 |
| {{field_confidence}} | 字段可信度信息 | 5 (最低) | 200 |
| {{error_message}} | 重试时的错误信息 | - | 不限（仅重试场景） |

## Validation Rules

- template_code: 字母、数字、下划线，3-50 字符，不可修改
- template_name: 2-100 字符
- content: 不能为空，最大 64KB
- change_summary: 最大 500 字符
- version_no: 模板内唯一，从 1 开始递增
- status: 只能取 DRAFT/PENDING_REVIEW/APPROVED/REJECTED
- is_active: 同一 template_id 下最多一个 APPROVED + is_active=1 版本
