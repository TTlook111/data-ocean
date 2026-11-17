# 001 用户与认证

正式入口位于 `backend/src/main/java` 与 `frontend/src`。Java 管理 Cookie 会话、人员及部门，角色使用权和授予上限见[权限源码](007-iam-s1.md)。运行及开发账号见[运行与验收](../backend/运行与验收.md)。

## 登录与本人

`/api/v1/auth` 提供 CSRF、登录、当前身份、退出与首次改密；`/api/v1/account` 提供本人资料、联系方式和正常改密。Spring Security 与 Spring Session Redis 保存服务端身份，密码使用带算法标识的 PBKDF2 编码。逐请求重核账号状态及认证版本，改密使旧会话失效，身份轮询不续期。

[认证接口](../../backend/src/main/java/com/dataocean/module/iam/controller/AuthController.java)、[认证服务](../../backend/src/main/java/com/dataocean/module/iam/service/impl/AuthServiceImpl.java)、[本人接口](../../backend/src/main/java/com/dataocean/module/iam/controller/AccountController.java)、[身份过滤器](../../backend/src/main/java/com/dataocean/module/iam/security/CurrentIdentityFilter.java)、[安全装配](../../backend/src/main/java/com/dataocean/module/iam/config/IamSecurityConfiguration.java)。

React 对应[登录与首次改密](../../frontend/src/modules/iam/LoginPage.tsx)、[账号信息](../../frontend/src/modules/iam/AccountPage.tsx)及[会话边界](../../frontend/src/shared/session.tsx)。布局与交互继续承接原型，不从被替换的 Vue 页面推定当前实现。

## 人员与部门

人员支持服务端分页、详情、新增及编辑、主兼任归属、个人角色、重置密码、停用、复核恢复与归档。组合保存逐项核权，版本或核对凭据变化不覆盖先提交的内容。归档保留当时组织和角色名称，并结束当前关系。

部门支持树、按人去重的汇总、直属成员与子部门分页、资料维护、批量加入兼任及迁移、停用、复核恢复与归档。配置锁、聚合版本和数据库约束共同阻止循环、重复当前关系及批量半成功。

[人员接口](../../backend/src/main/java/com/dataocean/module/iam/controller/UserController.java)、[人员服务](../../backend/src/main/java/com/dataocean/module/iam/service/impl/UserServiceImpl.java)、[部门接口](../../backend/src/main/java/com/dataocean/module/iam/controller/DepartmentController.java)、[部门服务](../../backend/src/main/java/com/dataocean/module/iam/service/impl/DepartmentServiceImpl.java)、[关系保存](../../backend/src/main/java/com/dataocean/module/iam/service/impl/AssignmentServiceImpl.java)、[建库基线](../../backend/src/main/resources/db/migration/)。

前端为[人员页](../../frontend/src/modules/iam/UsersPage.tsx)、[人员操作](../../frontend/src/modules/iam/UserDialogs.tsx)、[部门页](../../frontend/src/modules/iam/DepartmentsPage.tsx)和[部门操作](../../frontend/src/modules/iam/DepartmentDialogs.tsx)。验证范围见[首批验收](../backend/iam/验收.md)。
