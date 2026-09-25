# Frontend CLAUDE.md — DataOcean 前端开发指南

本文件为 AI 辅助开发前端页面时的强制参考。所有新页面、组件修改必须遵循此文件中的规范。

## 项目定位

DataOcean 是企业级 NL2SQL 智能数据查询与治理平台。前端服务两类用户：
- **管理员/治理人员**（B端）：通过 `/admin/*` 路由管理用户、数据源、元数据、权限
- **业务查询人员**（C端）：通过 `/query/*` 路由用自然语言查询数据库

同一个 Vue 项目，通过路由 + 权限控制区分。不是两个独立应用。

## 权限模型（核心）

**前端不持有角色或权限数组，也不再按权限码裁剪任何东西。**
判权的唯一来源是 Java 返回的 IAM-SIMPLE-1 能力摘要（capability snapshot）。

### 数据流

```
登录 → LoginResult { token, user }（已不含 roles / permissions）
     → Pinia auth store 只存 token 与身份字段
     → 需要判权时：useIamS1Store().load() → 拉取能力摘要
     → 组件按能力决定可见性与可用性
```

- `api/auth.ts` 的 `LoginResult` / `CurrentUser` **不再有** `roles` / `permissions` 字段。
- 旧写法 `auth.user?.permissions?.includes('xxx:manage')` **已失效**：`auth.user` 上根本没有该字段，
  表达式恒为 `false`，会把有权限的按钮判成无权限。**不要再用。**
- Java 始终在服务端强制校验；前端能力摘要只影响显示，**不是**安全边界。

### 能力摘要 API（`stores/iamS1.ts`）

| 名称 | 用途 |
| --- | --- |
| `iamS1.load(force?)` | 拉取能力摘要（幂等，已加载则跳过；`force` 强制刷新） |
| `iamS1.hasAnyAdminCapability` | 能否进入 `/admin/*`（路由守卫用） |
| `iamS1.systemAdmin` | 是否为系统管理员 |
| `iamS1.hasGlobal(code)` | 是否拥有某个全局功能码 |
| `iamS1.canOnDatasource(code, datasourceId?)` | 在指定数据源（或数据集）上是否拥有该功能码 |
| `iamS1.queryUse` / `viewSql` / `exportResult` | 问数、查看 SQL、导出结果三项用户端能力 |
| `iamS1.globalFunctions` / `datasourceCapabilities` | 原始能力列表（优先用上面的读方法） |
| `iamS1.errorMessage` | 摘要读取失败原因；页面必须显式展示，**不得降级为空能力或全零统计** |

功能码是 Java 侧冻结的固定目录（54 项，如 `security:permission:view`、`governance:check`），
前端**不得自造**功能码。

### 前端权限使用规则

1. **导航可见性**：七个一级业务域与二级工作区均放在左侧侧栏，`router/adminNavigation.ts` 的
   `ADMIN_WORKSPACES` 是唯一工作区元数据来源；内容区不得恢复全局二级导航。

   **导航项的三条约定**：
   - **高亮判定用路由显式声明的 key，不用路径前缀匹配。** `AdminDomainNav` 用 `route.meta.domainKey`，
     侧栏二级入口用 `route.meta.workspaceKey` 做等值比较。路径前缀匹配会因 `/admin/governance` 是
     `/admin/governance/issues` 的前缀而同时高亮两项。新增路由时必须填对这两个 meta。
   - **导航链接要继承跨工作区上下文。** 通过 `utils/adminNavigation.ts` 的
     `buildContextQuery(contextMode, source)` 构造 `:to`，按目标工作区的 `contextMode` 决定是否带上
     `datasourceId` / `snapshotId`（来源优先 URL 参数，缺失时回落 `adminContext` store）。用白名单构造，
     **不要**复制当前 `route.query`——`tab`、`page`、筛选项属于页面本地状态，泄漏到目标页会与目标页
     默认值冲突。目的：URL 自描述，可分享、可在刷新和前进后退时恢复。
   - **一级域的跳转目标由 `findDomainHome(domainKey)` 从 `ADMIN_WORKSPACES` 推导**，不要在导航组件里
     硬编码路径。曾出现 `access` 域被写成裸域路径 `/admin/access`，而路由表只有 `/admin/access/iam`
     等子路径，点击直接落到 404。`utils/adminNavigation.test.ts` 现有断言守着这条不变量。
2. **路由守卫**：`router/guards.ts` 先 `await iamS1.load()`，再用 `iamS1.hasAnyAdminCapability` 决定能否进
   `/admin/*`，否则重定向 `/query`。后台路由**不使用** `meta.permission`。能力摘要读取失败时按"无能力"
   处理，**不静默回退旧权限**。
3. **页面内按钮/操作**：按能力码判断，例如：
   ```vue
   const iamS1 = useIamS1Store()
   const canViewGrants = computed(() => iamS1.hasGlobal('security:permission:view'))
   const canQuery = computed(() => iamS1.queryUse)
   ```
   S1 正式页面不做前端细粒度拦截：页面按能力摘要显示明确中文提示，后端始终强制校验。
4. **工作台首页**：按能力摘要展示不同的待办、风险、生命周期状态和统计卡片。管理员看治理入口，
   普通用户看查询入口。
5. **新增后台页面归属**：新增 `/admin/*` 页面前，先读
   `../docs/development/completed/DataOcean后台重构状态与整改计划.md` 的导航决策（工作台、数据接入、数据资产、
   数据治理、语义中心、权限与组织、运营与平台）和该业务域下已有的二级工作区。一级与二级均放在侧栏；
   不要在业务域外新建一级入口，详情页和 Tab 不作为二级菜单。

### 旧权限体系已删除（2026-09-25）

轨道 B 的 B6 已移除旧权限体系：`sys_role`、`sys_permission`、`sys_user_role`、`sys_role_permission`、
`datasource_access`、`datasource_access_policy` 六张表已在 `V58` 删除，对应的 Controller、Service、
前端页面与 API 模块均已删除。因此：

- `/api/admin/roles`、`/api/admin/permissions`、`/api/admin/access-policies`、
  `/api/admin/datasource-access`、`/api/admin/access-approvals` **都不存在了**，新代码不得引用。
- `frontend/src/api/admin/permission.ts` 与 `api/query.ts` 已删除；问数走 `api/iamS1.ts`。
- 权限与组织域只有三个正式页面：`/admin/access/iam`（授权配置）、`/admin/access/iam-approvals`
  （访问申请与审批）、`/admin/access/iam-organization`（组织、角色与负责源）。
- 用户与部门仍由 `/api/admin/users`、`/api/admin/departments` 管理（`api/admin/user.ts`），
  但 `UserItem` 上的 `roleIds` / `roleNames` / `roleCodes` **固定为空**——角色改由 S1 绑定表达，
  不要把旧角色显示成新权限角色。
- `/admin/users`、`/admin/roles`、`/admin/departments`、`/admin/datasources` 是重构前的旧 URL，
  不兼容也不重定向；新代码只能引用正式路由。

## 视觉规范

### 参考风格

参考 `specs/img.png`，整体风格为：
- 现代扁平化管理后台
- 左侧固定侧边栏同时展示一级业务域和当前域下的二级工作区；内容区顶部不再重复二级导航
- 顶部栏显示当前页面标题 + 用户信息
- 内容区使用卡片式布局，圆角 8px，柔和阴影
- 配色以蓝绿为主调，暖色点缀

### 设计令牌（CSS Variables）

唯一定义在 `src/styles/variables.css`，所有页面必须使用这些变量（`src/style.css` 只引用这些变量，不重复定义）：

| 变量 | 用途 |
|------|------|
| `--do-primary` | 主色（蓝 #4d8fdc） |
| `--do-primary-strong` | 主色深（#2f73bd） |
| `--do-primary-soft` | 主色浅背景（#eef8ff） |
| `--do-accent` | 强调色（绿 #6aa84f） |
| `--do-ink` | 正文色（#243126） |
| `--do-muted` | 次要文字（#64735f） |
| `--do-surface` | 卡片/面板背景（#fffdf6） |
| `--do-line` | 边框色（#dbe7cf） |
| `--do-shadow` | 统一阴影 |
| `--do-bg` | 页面背景（#f5fbef） |

### 页面结构模式

每个内容页面遵循统一结构：

```vue
<template>
  <main class="xxx-page post-login-page">
    <!-- 页面头部：标题 + 描述 + 主操作按钮 -->
    <header class="page-header">
      <div>
        <p>分类标签</p>
        <h1>页面标题</h1>
        <span class="header-subtitle">一句话说明页面用途</span>
      </div>
      <el-button type="primary">主操作</el-button>
    </header>

    <!-- 筛选工具栏（列表页） -->
    <section class="toolbar">...</section>

    <!-- 主内容区 -->
    <section class="content-panel">...</section>

    <!-- 分页（列表页） -->
    <el-pagination class="pager" ... />
  </main>
</template>
```

### 卡片样式

```css
.card {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
  padding: 18px;
}
```

### 图标

统一使用 `lucide-vue-next`，不使用 Element Plus 内置图标。尺寸：
- 导航菜单：18px
- 卡片图标：20-22px
- 按钮内图标：16px

## 组件库使用

- UI 框架：Element Plus
- 表格：`el-table` + `el-table-column`
- 表单：`el-form` + `el-form-item`
- 弹窗：`el-dialog`
- 消息：`ElMessage` / `ElMessageBox`
- 加载态：`components/common/LoadingState.vue`（`variant="skeleton"` 首次加载 / `variant="spinner"` 局部刷新），表格刷新可用 `v-loading`
- 空状态：`components/common/EmptyState.vue`（必须说明为空原因，并提供可执行下一步）
- 错误态：`components/common/ErrorState.vue`（接口失败必须显示错误，不得降级为空数据或全零统计）
- 后台页面标题区：`components/admin/TaskPageHeader.vue`，不要自行拼装第二套标题区

## 状态管理

- Pinia store 在 `src/stores/`
- auth store 是核心，包含 token、user（登录返回）、currentUser（/me 返回）
- 页面级状态用组件内 `ref/reactive`，不需要全局 store

## API 调用模式

```typescript
// src/api/xxx.ts
import { http } from './http'

interface ApiResult<T> { code: number; message: string; data: T }

export async function listXxx(query: XxxQuery) {
  const { data } = await http.get<ApiResult<PageResult<XxxItem>>>('/api/admin/xxx', { params: query })
  return data
}
```

## 错误处理模式

```typescript
function extractError(error: unknown, fallback: string): string {
  if (typeof error === 'object' && error !== null && 'response' in error) {
    const msg = (error as any).response?.data?.message
    if (typeof msg === 'string') return msg
  }
  return fallback
}
```

## 页面开发检查清单

开发任何新页面前，确认：
1. [ ] 该页面的目标用户是谁？属于哪个一级业务域和二级工作区？
2. [ ] 路由是否使用了状态文档导航决策中的正式 URL，且 meta 包含 `title` / `domainKey` / `workspaceKey` / `contextMode`？（后台路由**不**添加 `meta.permission`）
3. [ ] 页面归属和侧栏层级是否符合 `../docs/development/completed/DataOcean后台重构状态与整改计划.md`？
4. [ ] 如果需要导航入口，是加到 `router/adminNavigation.ts` 的 `ADMIN_WORKSPACES`，而不是侧边栏直接加技术模块？
5. [ ] 如果页面需要数据源/快照范围，是否复用了 `ScopeBar` 与 `adminScope`，而没有在页面内另建一套数据源选择器？
6. [ ] 状态和允许的操作是否**以后端返回的状态为准**，而不是前端自行推断？阻断原因是否直接复用 readiness 的 `blockReasons`？
7. [ ] 是否使用了统一的 `TaskPageHeader` / `LoadingState` / `EmptyState` / `ErrorState` / `BusinessStatusBadge`？
8. [ ] URL 刷新、浏览器前进后退能否恢复必要状态（详情对象用路径参数，Tab 和筛选用查询参数）？
9. [ ] 加载态、空状态、错误态是否都处理了？加载中不得闪现“暂无数据”，接口失败不得显示全零统计？
10. [ ] 是否使用了 `src/styles/variables.css` 中的 CSS 变量而非硬编码颜色？
11. [ ] 图标是否来自 lucide-vue-next？
