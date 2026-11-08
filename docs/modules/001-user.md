# 001 用户与认证

旧版由 Java 管理用户、部门和登录身份。业务数据授权、功能权限与负责数据源由 [IAM](007-iam-s1.md) 单独判定。

## 登录与会话

`/api/auth` 提供验证码、登录、登出、读取本人身份、修改密码和更新个人资料。登录时校验验证码、账号状态和密码，达到失败阈值后锁定账号。密码使用 BCrypt 校验。登出将令牌加入 Redis 黑名单，账号令牌版本参与后续请求校验。[认证接口](../../backend/DataOcean/src/main/java/com/dataocean/module/user/controller/AuthController.java) · [认证处理](../../backend/DataOcean/src/main/java/com/dataocean/module/user/service/impl/AuthServiceImpl.java) · [验证码](../../backend/DataOcean/src/main/java/com/dataocean/module/user/service/impl/CaptchaServiceImpl.java) · [登录页](../../frontend/src/views/LoginView.vue)

## 用户与部门管理

- 用户管理支持筛选分页、新建、编辑、启停、删除、重置密码和 CSV 导出。导出单独鉴权，按筛选条件逐页读取账号。[用户接口](../../backend/DataOcean/src/main/java/com/dataocean/module/user/controller/UserController.java) · [用户服务](../../backend/DataOcean/src/main/java/com/dataocean/module/user/service/impl/UserServiceImpl.java)
- 部门维护提供树形查询及增删改。账号、部门关系保存在 `sys_user`、`sys_department`；部门变更与角色授权通过独立服务处理。[部门服务](../../backend/DataOcean/src/main/java/com/dataocean/module/user/service/impl/DepartmentServiceImpl.java) · [持久化对象](../../backend/DataOcean/src/main/java/com/dataocean/module/user/entity/po)
- 组织管理页组合用户列表和部门树，个人资料和改密通过当前登录身份调用。[组织管理页](../../frontend/src/views/admin/access/IamS1OrganizationView.vue) · [个人资料页](../../frontend/src/views/profile/ProfileView.vue)
