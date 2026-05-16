# Implementation Plan: 血缘与审计模块

**Branch**: `001-full-module-specs` | **Date**: 2026-05-16 | **Spec**: [spec.md](spec.md)

## Summary

血缘与审计模块记录每次查询的完整生命周期（谁问了什么、生成了什么 SQL、用了哪些表字段、结果如何），并从成功 SQL 中解析表级和字段级血缘关系。同时管理 LLM 调用成本日志和查询配额策略。

## Technical Context

**Language/Version**: Java 17 (Spring Boot 3.x)

**Primary Dependencies**: Spring Boot 3.x, MyBatis-Plus, Spring Scheduling

**Storage**: MySQL 8 (平台管理库), 大表考虑按月分表

**Testing**: JUnit 5 + MockMvc + Testcontainers

**Target Platform**: Linux server (Docker)

**Performance Goals**: 审计写入 < 100ms, 日志查询 < 1s (带索引), 血缘解析异步不阻塞主流程

**Constraints**: 180 天数据保留, 慢查询阈值 5s

## Project Structure

```text
backend/src/main/java/com/dataocean/module/audit/
├── controller/
│   ├── AuditLogController.java
│   ├── LineageController.java
│   └── QuotaController.java
├── service/
│   ├── AuditLogService.java
│   ├── LineageService.java
│   ├── LlmUsageService.java
│   ├── QuotaService.java
│   └── AuditCleanupJob.java
├── mapper/
│   ├── QueryAuditLogMapper.java
│   ├── QueryLineageTableMapper.java
│   ├── QueryLineageColumnMapper.java
│   ├── LlmUsageLogMapper.java
│   └── QuotaPolicyMapper.java
├── entity/
│   ├── QueryAuditLog.java
│   ├── QueryLineageTable.java
│   ├── QueryLineageColumn.java
│   ├── LlmUsageLog.java
│   └── QuotaPolicy.java
└── dto/
    ├── AuditLogQueryRequest.java
    ├── AuditLogVO.java
    ├── LineageVO.java
    ├── SlowQueryVO.java
    └── PromoteTemplateRequest.java

backend/src/main/resources/db/migration/
├── V12__create_audit_tables.sql
└── V13__create_quota_tables.sql
```

## Implementation Phases

### Phase 1: 审计日志记录

- 创建 query_audit_log 表和 Flyway 迁移
- 实现 AuditLogService：在查询完成后异步写入审计记录
- 记录字段：user_id, datasource_id, question, sql_text, execution_time_ms, row_count, is_success, error_message, is_slow
- 慢查询自动标记（execution_time_ms > 5000）

### Phase 2: 血缘解析

- 创建 query_lineage_table 和 query_lineage_column 表
- 接收 Python 层返回的 sqlglot 解析结果（used_tables, used_columns）
- 存储表级血缘（source_table, relation_type: FROM/JOIN/SUBQUERY）
- 存储字段级血缘（source_table, source_column, expression, alias_name）

### Phase 3: LLM 成本与配额

- 创建 llm_usage_log 和 quota_policy 表
- 记录每次 LLM 调用的 token 消耗和成本
- 实现配额策略：按用户/部门/数据源维度限制每日查询次数和月度成本

### Phase 4: 管理功能

- 审计日志查询 API（多维度筛选、分页、导出）
- 慢查询列表 API
- 模板提升 API（高频成功查询 → 查询模板）
- 数据清理定时任务（保留 180 天，可配置）

## Key Design Decisions

1. **审计写入异步化**: 使用 Spring @Async 或事件机制，不阻塞查询主流程
2. **血缘数据来源于 Python**: Java 不重复解析 SQL，直接存储 Python sqlglot 的解析结果
3. **慢查询阈值可配置**: application.yml 中配置 `dataocean.audit.slow-query-threshold-ms: 5000`
4. **数据保留策略**: Spring Scheduled 定时任务每天凌晨清理过期数据，保留天数可配置
5. **模板提升为人工操作**: 管理员从审计日志中选择高频成功查询手动提升，不做自动提升
