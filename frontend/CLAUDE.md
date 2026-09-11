# Frontend CLAUDE.md — DataOcean 前端开发指南

本文件为 AI 辅助开发前端页面时的强制参考。所有新页面、组件修改必须遵循此文件中的规范。

## 项目定位

DataOcean 是企业级 NL2SQL 智能数据查询与治理平台。前端服务两类用户：
- **管理员/治理人员**（B端）：通过 `/admin/*` 路由管理用户、数据源、元数据、权限
- **业务查询人员**（C端）：通过 `/query/*` 路由用自然语言查询数据库

同一个 Vue 项目，通过路由 + 权限控制区分。不是两个独立应用。

## 权限模型（核心）

### 数据流

```
登录 → LoginResult { roles, permissions } → Pinia auth store → 组件消费
     → 同时调 /api/auth/me 获取 CurrentUser 补充信息
```

### 权限粒度

| 权限标识 | 含义 | 对应页面/功能 |
|---------|------|-------------|
| `*` | 超级管理员，拥有所有权限 | 全部 |
| `user:manage` | 用户管理 | `/admin/access/organization?tab=users` |
| `role:view` | 角色查看 | `/admin/access/organization?tab=roles` |
| `department:manage` | 部门管理 | `/admin/access/organization?tab=departments` |
| `datasource:manage` | 数据源管理 | `/admin/data-sources` |
| （无特殊权限） | 普通用户 | `/query`, `/profile` |

> `/admin/users`、`/admin/roles`、`/admin/departments`、`/admin/datasources` 是重构前的旧 URL，现由 `router/index.ts` 重定向到上表的新路由。新代码不要引用旧 URL。

### 前端权限使用规则

1. **导航可见性**：`AdminShell.vue` 使用“一级业务域侧边栏 + 内容区二级工作区导航”。一级业务域和二级工作区都在 `router/adminNavigation.ts` 的 `ADMIN_WORKSPACES` 中定义，由 `AdminDomainNav.vue` 和 `AdminWorkspaceNav.vue` 渲染。
   > 后台前端整体重构期间（阶段 0–7）**不按旧权限码裁剪菜单**：使用真实 `*` 超级管理员账号，七个业务域和工作区全部可见。菜单权限的重新设计是重构完成后另立的独立任务，详见 `docs/development/guides/DataOcean-后台前端整体重构开发指导.md` 第 13 节。
2. **路由守卫**：`guards.ts` 只做登录校验和后台入口校验（`hasAdminAccess`），无后台权限时重定向到 `/query`。后台路由**不使用** `meta.permission`，不要在新路由上添加该字段。
3. **页面内按钮/操作**：需要在组件内根据 permissions 控制。模式：
   ```vue
   const auth = useAuthStore()
   const canManage = computed(() =>
     auth.user?.permissions?.includes('*') || auth.user?.permissions?.includes('xxx:manage')
   )
   ```
4. **工作台首页**：必须根据用户权限展示不同的待办、风险、生命周期状态和统计卡片。管理员看治理入口，普通用户看查询入口。
5. **新增后台页面归属**：新增 `/admin/*` 页面前，先读 `docs/development/guides/DataOcean-后台前端整体重构开发指导.md` 第 4 节的目标信息架构（工作台、数据接入、数据资产、数据治理、语义中心、权限与组织、运营与平台）和该业务域下已有的二级工作区；不要直接把技术页面加入侧边栏，也不要在业务域外新建一级入口。路由必须使用开发指导第 8 节冻结的目标 URL。

### 角色与页面对应关系

| 角色 | 可见页面 | 工作台展示重点 |
|------|---------|-------------|
| 超级管理员 | 全部 | 治理统计 + 所有快捷入口 |
| 数据治理员 | 数据源管理、元数据相关 | 数据源健康 + 治理任务 |
| 普通用户 | 问答端、个人资料 | 查询入口 + 最近查询 |

## 视觉规范

### 参考风格

参考 `specs/img.png`，整体风格为：
- 现代扁平化管理后台
- 左侧固定侧边栏只展示一级业务域，内容区顶部工作区导航展示二级功能入口（已实现在 `components/admin/AdminShell.vue`）
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
2. [ ] 路由是否使用了开发指导第 8 节冻结的目标 URL，且 meta 包含 `title` / `domainKey` / `workspaceKey` / `contextMode`？（后台路由**不**添加 `meta.permission`）
3. [ ] 页面归属是否符合 `docs/development/guides/DataOcean-后台前端整体重构开发指导.md` 第 4 节的信息架构？
4. [ ] 如果需要导航入口，是加到 `router/adminNavigation.ts` 的 `ADMIN_WORKSPACES`，而不是侧边栏直接加技术模块？
5. [ ] 如果页面需要数据源/快照范围，是否复用了 `ScopeBar` 与 `adminScope`，而没有在页面内另建一套数据源选择器？
6. [ ] 状态和允许的操作是否**以后端返回的状态为准**，而不是前端自行推断？阻断原因是否直接复用 readiness 的 `blockReasons`？
7. [ ] 是否使用了统一的 `TaskPageHeader` / `LoadingState` / `EmptyState` / `ErrorState` / `BusinessStatusBadge`？
8. [ ] URL 刷新、浏览器前进后退能否恢复必要状态（详情对象用路径参数，Tab 和筛选用查询参数）？
9. [ ] 加载态、空状态、错误态是否都处理了？加载中不得闪现“暂无数据”，接口失败不得显示全零统计？
10. [ ] 是否使用了 `src/styles/variables.css` 中的 CSS 变量而非硬编码颜色？
11. [ ] 图标是否来自 lucide-vue-next？
