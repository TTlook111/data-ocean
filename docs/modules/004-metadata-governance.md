# 004 元数据治理与可信度

> 文档类型：现行模块说明；核对日期：2026-10-20；治理规则运行批次与模板驱动实例实现已提交于 `fc2c066`。本轮核对代码与文档，V68 迁移和页面人工验收仍待独立完成，范围见[项目现状](../项目现状.md)。

## 概览与主流程

负责质量规则、真实检查、问题发现/分派/处理/复查、表字段治理状态，以及与可信度、标签和治理反馈的连接。

选定源与快照 → 质量检查 → 问题分派与处理 → 复查/必要时 REOPENED → 审核发布 → 查询血缘、使用与反馈回流。

## 关键边界

- DISCOVERED、NORMAL、RECOMMENDED、DEPRECATED、BLOCKED 等治理状态按现有服务规则流转，不等同于审核状态。
- DEPRECATED/BLOCKED 表字段禁止进入检索、SQL 生成和临时访问批准；NORMAL/RECOMMENDED 本身也不能跳过来源快照、审核、build membership 或当前 IAM 检查。
- 质量分与字段可信度是辅助决策，不代替权限。可信度读时衰减可配置，默认半衰期 30 天。
- 批量处理返回 `updated/skipped/skippedIssues`；跳过是正常部分成功结果，前端必须显示原因，不整体抛错或报全成功。
- 两个问题列表的 assigneeId 和目录的数据源范围均下推 SQL，禁止分页后再过滤。

## 代码与接口

| 代码 | 用途 |
| --- | --- |
| [MetadataGovernanceController](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/controller/MetadataGovernanceController.java) | `/api/admin`下 quality-check、quality-rules、quality-issues、治理状态与 review-records |
| [governance/checker](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/checker/) | 元数据维度与数据质量检查 |
| [governance/service](../../backend/DataOcean/src/main/java/com/dataocean/module/governance/service/) | 问题、状态、审核与质量聚合 |
| [fieldtag](../../backend/DataOcean/src/main/java/com/dataocean/module/fieldtag/) | 标签、可信度、反馈 |
| [QualityDashboard](../../frontend/src/views/admin/governance/QualityDashboard.vue)、[IssueList](../../frontend/src/views/admin/governance/IssueList.vue) | 正式质量与问题页面 |
| [governance API](../../frontend/src/api/admin/governance.ts) | 请求 DTO、筛选与批量返回 |

## 修改与验证

质量检查必须针对实际选定源和快照，记录 SQL 异常和结果；不以脚本静态通过宣称真实质量闭环。检查部分成功、无权、无快照、失败与复查。发布前和读取时仍检查治理准入，历史正常状态不能覆盖当前禁止状态。

## 质量规则模板与实例

规则实例通过 `templateCode` 选择已注册执行器；实例 `ruleCode` 只是独立身份，不参与处理器分派。模板注册表只公开当前有执行器的 15 项能力：

- 完整性：`COMP_TABLE_COMMENT_MISSING`、`COMP_COLUMN_COMMENT_MISSING`、`COMP_PRIMARY_KEY_MISSING`、`DATA_NULL_RATE_HIGH`。
- 准确性：`ACCU_TYPE_NAME_MISMATCH`、`ACCU_ENUM_ANOMALY`、`DATA_UNIQUE_VIOLATION`。
- 一致性：`CONS_CROSS_TABLE_TYPE_MISMATCH`、`CONS_CROSS_TABLE_COMMENT_CONFLICT`、`DATA_FK_ORPHAN`。
- 时效性：`TIME_SNAPSHOT_EXPIRED`、`TIME_TABLE_NO_UPDATE`、`DATA_STALE_TABLE`。
- 可追溯性：`TRACE_FK_MISSING`、`TRACE_ISOLATED_TABLE`。

模板提供维度、目标类型、允许范围和类型化参数定义；空值率、枚举基数、快照年龄、数据陈旧天数有上下界，唯一性模板另有“仅检查主键”布尔参数。不开放 SQL、脚本或表达式。未知旧规则编码不映射模板，也不会进入检查；列表明确标成未映射/不支持，不能通过随意选择模板让它执行。

实例保存名称、模板、说明、严重程度、扣分、参数、启用状态、范围及配置版本。范围为全局、数据源、表或字段；表/字段目标由已选数据源真实元数据选择，并按数据源 ID 加稳定名称解析到后续快照，不绑定旧快照行 ID。对象缺失或来源不匹配记录为不适用/失败原因，不回退到其他对象。模板声明允许的组合由服务端再次校验。

同模板和目标按全局 < 数据源 < 表 < 字段选择唯一有效配置，覆盖后不对相同检查重复扣分；同级的启用冲突拒绝保存或启用。编辑新增配置版本并记录变更人/时间；复制生成停用的新实例；停用保留配置；删除在单次操作内停用并软删除，恢复只恢复为停用。规则变更写入 `metadata_quality_rule_revision`，执行批次保留当时规则配置快照，因此当前编辑/删除不改写历史依据。迁移时只为当时可读取的旧配置写 `BASELINE`，不伪造迁移前事件。

恢复会在数据库中明确清除删除时间和删除人，并保持停用。规则编辑按完整配置保存，作用范围变更会清空不再适用的数据源、表和字段；返回对象及新增修订取自同一份配置。显式空值仅用于本次规则配置更新，不改变其他规则状态操作的字段更新策略。

## 批次、覆盖与可信结果

每次执行先固定启用规则与配置版本，创建 `quality_check_batch` 和逐规则 `quality_check_batch_rule` 覆盖记录。规则覆盖状态为 `SUCCESS`、`FAILURE`、`NOT_APPLICABLE`、`NOT_EXECUTED`，批次区分成功、部分失败、失败、运行中和仅有限可解释的旧结果。批次记录使用独立事务保存失败状态；检查器异常不再捕获后继续算分。没有适用规则、检查不完整或有失败时不生成“全部通过”结果，不按成功路径清理旧问题、更新分数、批准快照或覆盖此前可信完整结果。完整全量检查才按既有问题生命周期处理 OPEN/CONFIRMED；REOPENED 仍待处置。已发布快照保护和原有评分权重保留。

`DATA_FK_ORPHAN` 检查器接收执行计划筛选后的关系及其安全端点；检查器还会按表和字段治理状态再次拒绝受限关系。元数据类检查继续收到其所需的关系上下文。`DATA_STALE_TABLE` 的规划与执行共用候选字段选择和治理状态过滤；没有可读时间字段时记为不适用并阻止完整覆盖，有多个候选时只读取优先级最高的可用字段。修订历史按每条修订记录的数据源范围过滤；执行历史按真实批次数据源过滤，全局规则批次也不使用空规则归属推断访问范围，系统管理员仍可读取全部历史。

迁移前的 `quality_check_result` 若无法证明规则版本与覆盖，只显示为历史有限结果，不以当前配置补造历史。新数据级模板沿用业务数据源只读连接、固定安全查询与超时；不执行客户端 SQL。当前扫描最多使用 100,000 行样本，因此样本外重复值、空值或孤儿记录可能无法发现，结果说明以实际检查覆盖为准，不视为全表证明。

## 权限与页面

治理总览从 Java 恢复最近可核验批次，单独显示快照记录评分、最近检查结果和待处理问题全量统计；问题预览不用于推算总数。质量规则页提供模板、维度、范围和启用/删除筛选，以及详情、修订历史、编辑、复制、启停、软删除和停用恢复。资产状态维护仍在独立入口。

前端根据 IAM-SIMPLE-1 能力控制呈现；服务端最终授权。数据源规则写入要求该源 `governance:rule:manage`，改范围时同时核验原范围和目标范围；全局规则写入要求受保护系统管理员身份。权限不从旧角色或 JWT authorities 推导。变更修订保留真实操作人；通用操作审计继续记录请求操作。实现、定向检查与待人工视觉确认状态见[项目现状](../项目现状.md#2026-10-19-治理总览与质量规则管理改造)。

历史审查中的缺陷是当时记录。未复核的项目不直接写成当前缺陷或新待办；需先定位现存代码，再进入[后续开发](../development/后续开发.md)。
