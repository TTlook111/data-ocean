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
| enabled | TINYINT | NOT NULL, DEFAULT 1 | 1=启用, 0=禁用 |
| version | INT | NOT NULL, DEFAULT 0 | 乐观锁版本 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |
| updated_at | DATETIME | NOT NULL, DEFAULT NOW() ON UPDATE | 更新时间 |

### prompt_template_version

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 版本ID |
| template_id | BIGINT | FK → prompt_template.id, NOT NULL | 所属模板 |
| version_no | INT | NOT NULL | 版本号（模板内递增） |
| content | TEXT | NOT NULL | 该版本的模板内容 |
| change_summary | VARCHAR(500) | | 修改摘要 |
| created_by | BIGINT | FK → sys_user.id | 修改人 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |

UNIQUE INDEX: (template_id, version_no)

## Initial Data (Flyway V10)

5 个核心模板初始记录：

| template_code | template_name | scenario |
|---------------|---------------|----------|
| sql_generation | SQL 生成 | query |
| chart_generation | 图表生成 | chart |
| intent_recognition | 意图识别 | intent |
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
