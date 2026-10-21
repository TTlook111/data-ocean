# 001 用户与认证

> 文档类型：现行模块说明；核对日期：2026-10-17；代码基线：`619bca6`。本轮静态核对，运行验收见[项目现状](../项目现状.md)。

## 概览与边界

负责登录、验证码、用户和部门管理、个人资料、密码及 JWT 失效。功能角色、负责源和业务数据授权归[IAM-SIMPLE-1](007-iam-s1.md)，不再使用旧角色权限表或 JWT authority 计算授权。

主流程：验证码与登录 → Java 身份校验与 JWT → 前端保存身份和令牌 → S1 能力摘要 → 服务端每次操作判权。前端隐藏按钮不构成安全边界。

## 当前行为

- 登录失败计数、锁定、首次改密与密码重置由认证/用户服务处理；密码使用 BCrypt。
- `/api/auth/me`提供当前身份；登录返回值不再作为角色、权限数组的授权来源。
- JWT 黑名单与 `user:token-version:{userId}`仍在用。尚未建立并验收替代机制前，不删除 `tokenVersion`。
- 部门保留真实组织信息；角色绑定和数据授权独立配置，不映射旧角色。
- 用户实体位于 `entity.po`；列表使用 `entity.query.UserQuery` 继承公共 `PageQuery`。页码、排序规则及字段白名单见[架构说明](../技术栈与模块职责.md#java-对象目录与分页查询)。CSV 导出使用标准 `pageNo` 逐页读取，覆盖传入页码，避免别名遮蔽；每页沿用公共上限，按实际返回页数判断结束，避免超过 100 条时提前结束导出。
- 内部令牌与 JWT 密钥都无可用默认值；配置规则见[开发指南](../development/本地开发与验证.md)。

## 关键入口

| 入口 | 用途 |
| --- | --- |
| [AuthController](../../backend/DataOcean/src/main/java/com/dataocean/module/user/controller/AuthController.java) | `/api/auth`下 captcha、login、logout、me、password、profile |
| [UserController](../../backend/DataOcean/src/main/java/com/dataocean/module/user/controller/UserController.java) | `/api/admin/users`管理 |
| [DepartmentController](../../backend/DataOcean/src/main/java/com/dataocean/module/user/controller/DepartmentController.java) | `/api/admin/departments`管理 |
| [common/security](../../backend/DataOcean/src/main/java/com/dataocean/common/security/) | JWT、认证过滤、当前用户和内部认证 |
| [前端 auth API](../../frontend/src/api/auth.ts)、[用户 API](../../frontend/src/api/admin/user.ts) | 身份与账号请求 |
| [IAM store](../../frontend/src/stores/iamS1.ts) | 当前能力摘要 |

登录页是 [LoginView.vue](../../frontend/src/views/LoginView.vue)。旧 `RoleController`、`/api/admin/roles`和 `sys_role/sys_permission/sys_user_role/sys_role_permission`已由 B6/V58 退休，不能按旧模块文档恢复。

## 修改与验证

改令牌失效、密码或内部认证时核对相关安全测试；改角色与权限转到 IAM 模块。数据库初始建表脚本保留历史，不代表旧权限表仍存在。迁移必须顺序核对，不能直接重跑初始脚本。
