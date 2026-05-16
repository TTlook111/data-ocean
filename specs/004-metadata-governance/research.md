# Technical Research: 元数据治理模块

**Date**: 2026-05-16

## Decision 1: 质量校验引擎架构

**Options**:
1. Drools 规则引擎 — 企业级，支持动态规则
2. 策略模式 + 硬编码规则 — 简单直接
3. SpEL 表达式引擎 — Spring 原生，支持动态表达式
4. Groovy 脚本引擎 — 动态加载规则脚本

**Decision**: 方案 2 — 策略模式

**Rationale**:
- MVP 阶段规则数量有限（约 15-20 条内置规则），硬编码足够
- Drools 学习成本高，引入复杂度不值得
- 策略模式清晰：每个 Checker 实现 `QualityChecker` 接口，返回 `List<QualityIssue>`
- 后续迭代可平滑升级为 SpEL 表达式（规则存数据库，SpEL 解析执行）

**Interface Design**:
```java
public interface QualityChecker {
    String dimension();  // completeness/accuracy/consistency/timeliness/traceability
    List<QualityIssue> check(MetadataSnapshot snapshot, List<QualityRule> rules);
}
```

## Decision 2: 质量分计算算法

**Options**:
1. 简单平均 — 五维等权
2. 加权平均 — 按重要性加权
3. 扣分制 — 满分 100，每个问题扣分

**Decision**: 方案 3 — 扣分制

**Rationale**:
- 更直观：满分 100，每个 HIGH 问题扣 5 分，MEDIUM 扣 2 分，LOW 扣 0.5 分
- 最低分 0，不出现负数
- 五维各自独立计算维度分，总分为五维加权平均（完整性 30%、准确性 25%、一致性 25%、时效性 10%、可追溯性 10%）
- 权重可配置（存 application.yml）

**Formula**:
```
dimension_score = max(0, 100 - sum(issue_deductions))
total_score = 0.30 * completeness + 0.25 * accuracy + 0.25 * consistency + 0.10 * timeliness + 0.10 * traceability
```

## Decision 3: 治理状态设计

**States**:
| 状态 | 含义 | RAG 准入 |
|------|------|----------|
| DISCOVERED | 新采集，未治理 | 不允许 |
| NORMAL | 已确认可用 | 允许 |
| RECOMMENDED | 推荐使用（高质量） | 允许（优先召回） |
| DEPRECATED | 已废弃，不建议使用 | 不允许 |
| SENSITIVE | 敏感字段，需脱敏 | 允许（标记脱敏） |
| BLOCKED | 阻断，禁止使用 | 不允许 |

**Transition Rules**:
- DISCOVERED → NORMAL/RECOMMENDED/DEPRECATED/SENSITIVE/BLOCKED（人工确认）
- NORMAL ↔ RECOMMENDED（升降级）
- NORMAL/RECOMMENDED → DEPRECATED（废弃）
- NORMAL/RECOMMENDED → SENSITIVE（标记敏感）
- 任意状态 → BLOCKED（紧急阻断）
- DEPRECATED/BLOCKED → NORMAL（恢复，需审核）

**Decision**: 治理状态存储在 db_table_meta.governance_status 和 db_column_meta.governance_status 字段中，不单独建表。原因：状态是元数据的属性，跟随快照版本管理。

## Decision 4: 问题清单生命周期

**States**:
```
OPEN → CONFIRMED → RESOLVED (修正完成)
     → REJECTED (误报驳回)
     → AUTO_CLOSED (新快照生成时自动关闭旧问题)
```

**Design**:
- 每次质量校验生成新的问题清单，关联到具体快照
- 新快照生成时，旧快照的 OPEN 状态问题自动标记为 AUTO_CLOSED
- 如果新快照中同样的问题仍存在，会重新生成新的 OPEN 问题
- 支持问题分派给指定用户（assignee_id）

## Decision 5: 内置质量规则清单（MVP）

### 完整性维度
1. 表注释缺失（HIGH）
2. 字段注释缺失（MEDIUM）
3. 主键缺失（HIGH）
4. 字段注释过短（< 2 字符）（LOW）

### 准确性维度
5. 字段名含 time/date 但类型非时间类型（MEDIUM）
6. 字段名含 id 但类型非整数/字符串（LOW）
7. 空值率 > 90% 的非空字段（MEDIUM）
8. 枚举字段值超过 100 种（LOW）

### 一致性维度
9. 同名字段跨表类型不一致（HIGH）
10. 同名字段跨表注释不一致（MEDIUM）
11. 外键字段类型与目标主键不匹配（HIGH）

### 时效性维度
12. 表 UPDATE_TIME 超过 180 天未更新（LOW）
13. 快照距上次同步超过 30 天（MEDIUM）

### 可追溯性维度
14. 表无任何外键关系且无 _id 后缀字段（LOW）
15. 存在 _id 后缀字段但无对应外键定义（MEDIUM）

## Decision 6: 批量操作策略

**Problem**: 大库可能有 500+ 字段需要治理确认

**Solution**:
- 支持按表批量设置治理状态
- 支持按规则批量确认（如"所有无注释字段统一标记为需补充"）
- 支持 AI 辅助建议（后续迭代）：根据字段名、类型、统计信息自动建议治理状态
- MVP 先实现按表批量 + 单字段操作
