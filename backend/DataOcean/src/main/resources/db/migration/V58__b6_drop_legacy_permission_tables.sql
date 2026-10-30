-- B6：删除旧权限体系专用表
--
-- 背景：IAM-SIMPLE-1 已独立建立、完成切换并通过核心验收；旧权限体系的代码路径已全部删除
-- （批次 2 `735323e`、批次 3 `5721f8c`、`9d09007`）。本 migration 清理随之失去用途的表。
--
-- 前置核对（执行时已逐项确认）：
--   1. 全库 0 外键、0 视图、0 触发器（information_schema 实测），故 DROP 无依赖顺序问题；
--   2. Java 主代码与资源中已无任何对这些表名的引用（含 MyBatis 注解 SQL——
--      已删除 DatasourceMapper 中两个引用 datasource_access 的死方法）；
--   3. 这些表不参与任何新的权限计算，读写代码路径均已删除。
--
-- 刻意**不**删除的两张表（按冻结清单保留为只读历史，只删代码写入路径）：
--   permission_change_log    —— 「谁在何时给谁什么权限」的审计证据
--   access_approval_request  —— 历史申请与审批记录
-- 它们不再被任何代码写入或读取，删除不可恢复，故保留。
--
-- 明确不涉及：sys_user、sys_department、datasource、datasource_secret、元数据、治理、
-- 术语、知识、会话、查询任务、审计、通知等业务数据表一律不动。
--
-- 版本说明：V53 永久不使用（见 docs/development/后续开发.md 与 B0 §7），
-- 故此处使用当时未占用的下一个版本 V58。已执行的 migration 一律不修改，本文件只做前向删除。

DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_role_permission;
DROP TABLE IF EXISTS sys_permission;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS datasource_access;
DROP TABLE IF EXISTS datasource_access_policy;
