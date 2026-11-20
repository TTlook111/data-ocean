# DataOcean 前端

本目录为正式前端工程。新增、修改和重构前，从[开发前必读与完成验收](../docs/frontend/README.md#开发前必读与完成验收)进入，按顺序读取共用总则、设计与交互规范、模块专题和原型，完成前逐项核对。

技术选型、当前实现及验证边界统一维护在[前端开发文档](../docs/frontend/README.md)，跨端业务入口见[源码地图](../docs/source-map/README.md)。

项目工作约定见根目录 [AGENTS.md](../AGENTS.md)。

## 源码导览

### 应用入口

| 文件 | 职责 |
| --- | --- |
| [index.html](index.html)、[main.tsx](src/app/main.tsx) | 提供页面挂载点，装配路由、请求缓存和 Ant Design 主题 |
| [Application.tsx](src/app/Application.tsx) | 管理登录边界、后台外壳、菜单权限和业务路由 |
| [StatusPage.tsx](src/app/StatusPage.tsx)、[CurrentScene.tsx](src/app/CurrentScene.tsx)、[StatusPage.module.css](src/app/StatusPage.module.css) | 展示 404 和连接失败页面，提供恢复入口、交互画面及状态页样式 |
| [theme.ts](src/app/theme.ts)、[styles.css](src/app/styles.css) | 集中维护组件主题、后台布局及共用样式 |

### 人员与权限

[iam](src/modules/iam/) 承载登录、本人账号、人员、部门、角色和权限审计。交互细则见[人员角色与权限页面](../docs/frontend/pages/people-access.md)。

| 文件 | 职责 |
| --- | --- |
| [LoginPage.tsx](src/modules/iam/LoginPage.tsx)、[AccountPage.tsx](src/modules/iam/AccountPage.tsx) | 登录、首次改密及本人资料与密码管理 |
| [UsersPage.tsx](src/modules/iam/UsersPage.tsx)、[UserDialogs.tsx](src/modules/iam/UserDialogs.tsx) | 人员列表和详情，以及编辑、角色分配和管理操作弹窗 |
| [DepartmentsPage.tsx](src/modules/iam/DepartmentsPage.tsx)、[DepartmentDialogs.tsx](src/modules/iam/DepartmentDialogs.tsx) | 部门树、成员与部门配置入口，以及编辑和管理操作弹窗 |
| [RolesPage.tsx](src/modules/iam/RolesPage.tsx) | 角色列表、详情及角色权限配置 |
| [AuditPage.tsx](src/modules/iam/AuditPage.tsx) | 审计列表、记录详情以及关联业务追溯入口 |
| [types.ts](src/modules/iam/types.ts) | 身份、角色、部门、权限和变更核对的接口类型 |

### 数据源接入与采集

[datasource](src/modules/datasource/) 承载数据源接入、采集记录、元数据目录、结构变化及企业业务说明。交互细则见[数据源页面专题](../docs/frontend/pages/datasources.md)。

| 文件 | 职责 |
| --- | --- |
| [DatasourcePage.tsx](src/modules/datasource/DatasourcePage.tsx) | 「数据源管理」的概况、详情、连接配置和分项测试 |
| [CredentialDialog.tsx](src/modules/datasource/CredentialDialog.tsx) | 测试新凭据、核对影响并追踪替换状态 |
| [CollectionPage.tsx](src/modules/datasource/CollectionPage.tsx) | 采集记录、任务详情及接入模块共用的数据源切换 |
| [CatalogPage.tsx](src/modules/datasource/CatalogPage.tsx) | 元数据目录和表、视图的结构详情 |
| [StructureChangePage.tsx](src/modules/datasource/StructureChangePage.tsx) | 查看结构差异、核实结论和来源证据 |
| [BusinessNotesPage.tsx](src/modules/datasource/BusinessNotesPage.tsx) | 企业业务说明的列表、编辑、修订与附件回看 |
| [Presentation.tsx](src/modules/datasource/Presentation.tsx)、[types.ts](src/modules/datasource/types.ts)、[datasource.css](src/modules/datasource/datasource.css) | 数据源模块共用的状态展示、接口类型和样式 |

### 数据访问授权

[access](src/modules/access/) 承载审批责任、共享规则、访问申请、授权管理和个人访问范围。业务规则及界面入口见[功能权限与数据访问](../docs/frontend/pages/people-access.md#功能权限与数据访问)。

| 文件 | 职责 |
| --- | --- |
| [ConfigurationPages.tsx](src/modules/access/ConfigurationPages.tsx) | 组装「数据源管理」的概况与审批责任入口，维护输出限制规则页面 |
| [RequestsPage.tsx](src/modules/access/RequestsPage.tsx) | 我的申请、审批队列、申请详情及办理操作 |
| [ScopePages.tsx](src/modules/access/ScopePages.tsx) | 授权管理、个人访问范围及来源回看，提供直属部门初始配置面板 |
| [AccessControls.tsx](src/modules/access/AccessControls.tsx)、[types.ts](src/modules/access/types.ts)、[access.css](src/modules/access/access.css) | 授权模块共用的列表、表单、状态、请求辅助、接口类型和样式 |

### 表说明与字段治理

`/governance` 和 `/governance/:id` 分别进入成功目录工作台和稳定对象详情。页面在 `src/modules/governance`，复用来源选择、筛选分页、原命令回读与危险操作核对。表说明生成按实际任务状态读取结果，建议可逐项填入草稿。验收边界见[C 批验收](../docs/backend/governance/验收.md)。

### 知识文档与发布准备

[modules/knowledge](src/modules/knowledge) 提供知识文档、标准题和发布准备。选择同一数据源，编辑或确认实际文档修订，并核对题单和模型前置。当前验收见[D 批首个事项](../docs/backend/knowledge/验收.md)。

### 共用组件与请求

| 文件 | 职责 |
| --- | --- |
| [api.ts](src/shared/api.ts)、[session.tsx](src/shared/session.tsx) | 封装 JSON 请求、CSRF 和错误处理，提供前端会话及操作权限判断 |
| [RecordTable.tsx](src/shared/RecordTable.tsx)、[Records.tsx](src/shared/Records.tsx) | 共用表格、分页、结果状态、详情底栏及状态与时间显示 |
| [ActionMenuButton.tsx](src/shared/ActionMenuButton.tsx) | 共用「更多」菜单及其键盘操作 |
| [ReviewDialog.tsx](src/shared/ReviewDialog.tsx)、[OperationContext.tsx](src/shared/OperationContext.tsx) | 共用操作核对弹窗和操作对象摘要 |
| [formErrors.ts](src/shared/formErrors.ts) | 将服务端字段错误映射到表单并聚焦首个错误 |
| [useFormClose.ts](src/shared/useFormClose.ts)、[useReturnFocus.ts](src/shared/useReturnFocus.ts) | 检测未保存修改，关闭控件后恢复触发位置的焦点 |

### 构建与测试

[package.json](package.json) 定义开发、构建和检查命令，[vite.config.ts](vite.config.ts) 维护构建、开发代理和 Vitest 配置。测试文件 `*.test.ts(x)` 与对应应用、业务模块和共用组件同目录，[setup.ts](src/shared/test/setup.ts) 提供组件测试的共用初始化。

## 本地开发

两台设备分别按本机私有环境说明选择兼容的 Node.js 与 npm，范围见[工程版本](../docs/frontend/README.md#工程版本与验证边界)，不需要安装在相同路径或使用相同补丁。每台设备拉取后，在本目录先核对 `node --version`、`npm --version` 并执行 `npm ci`，按同一锁文件安装本机依赖。

代理默认指向 `http://127.0.0.1:18080`，其他地址用 `DATAOCEAN_API_PROXY_URL` 覆盖。在本目录执行 `npm run dev` 启动，`npm run build` 构建，`npm run lint` 检查代码；从仓库根目录也可使用随项目提供的 `tools/dev/start-frontend.ps1`，完整说明见[运行与验收](../docs/backend/运行与验收.md)。
