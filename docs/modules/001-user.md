# 001 用户与认证

> 状态：现行模块说明；整理日期：2026-10-24。本文维护当前行为与契约，迁移/运行/验收结论统一见[项目现状](../项目现状.md)及[证据索引](../acceptance/README.md)。

## 配套功能定位

| 修改意图 | 文件与定位符号 | 关联影响 |
| --- | --- | --- |
| 用户增删改、启停与重置密码 | [UserServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/user/service/impl/UserServiceImpl.java)：`createUser`、`updateUser`、`deleteUser`、`updateStatus`、`resetPassword` | 账号管理不取代 S1 角色与授权配置。 |
| 部门树与部门维护 | [DepartmentServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/user/service/impl/DepartmentServiceImpl.java)：`tree`、`createDepartment`、`updateDepartment`、`deleteDepartment` | 组织变更与权限修订。 |
| 验证码生成和验证 | [CaptchaServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/user/service/impl/CaptchaServiceImpl.java)：`generate`、`verify` | 验证码生命周期与登录校验。 |

正式入口为 `/admin/access/iam-organization`，由 IamS1OrganizationView.vue 组合 UserList.vue、DepartmentTree.vue；验证码在 LoginView.vue。角色分配见[IAM 配置](iam/功能与数据授权.md)。本轮未定位到验证码专用测试。

验证入口：[UserServiceImplS1GuardTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/user/service/impl/UserServiceImplS1GuardTest.java)（用户管理边界）、[DepartmentServiceImplS1RevisionTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/user/service/impl/DepartmentServiceImplS1RevisionTest.java)（部门与 S1 修订）。本轮仅核对入口，未执行这些业务测试。

## 修改定位

先按修改意图定位符号，再阅读下方行为约束。源码定位核对日期：2026-10-26；不代表运行验收。

| 修改意图 | 文件与定位符号 | 关联影响 |
| --- | --- | --- |
| 登录、登出与改密 | [AuthServiceImpl.java](../../backend/DataOcean/src/main/java/com/dataocean/module/user/service/impl/AuthServiceImpl.java)：`login`、`logout`、`changePassword` | 令牌失效、密码校验与当前身份。 |
| 用户列表与 CSV 导出 | [UserController.java](../../backend/DataOcean/src/main/java/com/dataocean/module/user/controller/UserController.java)：`listUsers`、`exportUsers`、`escapeCsv` | 列表筛选、逐页读取、CSV 字段与转义；导出需要独立权限。 |

导出验证入口：[UserControllerExportTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/user/controller/UserControllerExportTest.java) 的 `exportsAllPagesWithinSizeLimitAndOverridesIncomingPageAliases`。修改导出字段还应核对 CSV 表头与每行写入顺序；该测试入口不能代表所有格式和安全场景均已覆盖。

沿 AuthController → AuthServiceImpl 阅读；权限能力变化转到 IAM，不在认证返回值增加旧权限来源。

验证入口：[AuthServiceImplTest.java](../../backend/DataOcean/src/test/java/com/dataocean/module/user/service/impl/AuthServiceImplTest.java)（认证服务）。本次只核对测试入口，修改实现后按[开发指南](../development/本地开发与验证.md)运行相关测试，再按影响范围补充接口或页面验证。

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
| [common/security](../../backend/DataOcean/src/main/java/com/dataocean/common/security) | JWT、认证过滤、当前用户和内部认证 |
| [前端 auth API](../../frontend/src/api/auth.ts)、[用户 API](../../frontend/src/api/admin/user.ts) | 身份与账号请求 |
| [IAM store](../../frontend/src/stores/iamS1.ts) | 当前能力摘要 |

登录页是 [LoginView.vue](../../frontend/src/views/LoginView.vue)。旧 `RoleController`、`/api/admin/roles`和 `sys_role/sys_permission/sys_user_role/sys_role_permission`已由 B6/V58 退休，不能按旧模块文档恢复。

## 修改与验证

改令牌失效、密码或内部认证时核对相关安全测试；改角色与权限转到 IAM 模块。数据库初始建表脚本保留历史，不代表旧权限表仍存在。迁移必须顺序核对，不能直接重跑初始脚本。
