# Implementation Plan: Prompt 管理模块

**Branch**: `014-prompt-management` | **Date**: 2026-05-16 | **Spec**: [spec.md](spec.md)

## Summary

Prompt 管理模块实现 Prompt 模板的数据库存储、版本管理、回滚和 Token 预算控制。Java 层负责模板 CRUD 和版本管理，Python 层通过内部 API 获取当前活跃版本并执行 Token 预算裁剪和变量渲染。

## Technical Context

**Language/Version**: Java 17 (Spring Boot 3.x) + Python 3.13 (FastAPI)

**Primary Dependencies**:
- Java: Spring Boot 3.x, MyBatis-Plus, Flyway
- Python: FastAPI, tiktoken (Token 计算), Jinja2 (模板渲染)

**Storage**: MySQL 8 (平台管理库)

**Testing**: JUnit 5 + MockMvc (Java), pytest + httpx (Python)

**Constraints**: 同一 template_code 同时只有一个 active 版本；Token 总预算 4000

## Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. 元数据治理优先 | N/A | Prompt 模块不涉及元数据 |
| II. SQL 安全与只读执行 | N/A | 操作管理库 |
| III. 三层分离架构 | PASS | Java 存储管理，Python 消费渲染 |
| IV. RAG 准入控制 | N/A | 不涉及 RAG |
| V. 可信度驱动生成 | PASS | Token 预算中包含 field_confidence 变量 |
| VI. 渐进式 MVP | PASS | 5 个核心模板，不做 A/B 测试 |

**Gate Result**: PASS

## Project Structure

```text
backend/src/main/java/com/dataocean/module/prompt/
├── controller/
│   └── PromptTemplateController.java
├── service/
│   ├── PromptTemplateService.java
│   └── PromptTemplateServiceImpl.java
├── mapper/
│   ├── PromptTemplateMapper.java
│   └── PromptTemplateVersionMapper.java
├── entity/
│   ├── PromptTemplate.java
│   └── PromptTemplateVersion.java
└── dto/
    ├── PromptTemplateVO.java
    ├── PromptTemplateUpdateRequest.java
    ├── PromptVersionVO.java
    └── PromptRollbackRequest.java

backend/src/main/resources/db/migration/
└── V10__create_prompt_tables.sql

python-service/dataocean/prompt/
├── router.py              # /internal/prompts/{template_code}
├── service.py             # 模板获取 + 缓存
├── renderer.py            # 变量渲染 + Token 预算裁剪
└── token_budget.py        # Token 计算与优先级裁剪逻辑
```

## Implementation Phases

### Phase 1: Java 模板 CRUD + 版本管理

1. Flyway 迁移脚本创建 prompt_template 和 prompt_template_version 表
2. 实现 MyBatis-Plus Entity + Mapper
3. 实现 Service 层：列表查询、更新（自动创建新版本）、回滚、启用/禁用
4. 实现 Controller：管理端 API
5. Flyway 初始化 5 个核心模板的默认内容

### Phase 2: Python 内部 API + Token 预算

1. 实现 /internal/prompts/{template_code} 端点（调用 Java API 或直连管理库）
2. 实现 Token 计算（tiktoken qwen 兼容 tokenizer）
3. 实现优先级裁剪逻辑：schema(1500) > skills(1000) > few-shot(800) > context(500) > confidence(200)
4. 实现 Jinja2 变量渲染器

### Phase 3: 集成与测试

1. Java 单元测试：版本自增、回滚、并发乐观锁
2. Python 单元测试：Token 裁剪边界、变量缺失处理
3. 集成测试：Java 保存 → Python 获取最新版本

## Key Design Decisions

- **版本管理策略**: 每次 PUT 保存自动创建新版本，version_no 递增，新版本自动设为 active
- **回滚实现**: 不复制内容创建新版本，而是直接将目标版本设为 active（简单高效）
- **乐观锁**: prompt_template 表增加 version 字段，防止并发编辑冲突
- **Python 获取方式**: Python 通过 httpx 调用 Java 内部 API `/internal/prompts/{template_code}`（保持 Java 为唯一数据源）
- **Token 计算**: 使用 tiktoken cl100k_base 编码（与 Qwen 兼容），预算超出时从最低优先级开始截断
