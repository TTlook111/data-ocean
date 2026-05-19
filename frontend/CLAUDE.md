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
| `user:manage` | 用户管理 | /admin/users |
| `role:view` | 角色查看 | /admin/roles |
| `department:manage` | 部门管理 | /admin/departments |
| `datasource:manage` | 数据源管理 | /admin/datasources |
| （无特殊权限） | 普通用户 | /query, /profile |

### 前端权限使用规则

1. **菜单可见性**：AppShell.vue 的 `canView()` 已处理，菜单项带 `permission` 字段的会自动过滤
2. **路由守卫**：guards.ts 已拦截无权限路由访问，重定向到 /admin
3. **页面内按钮/操作**：需要在组件内根据 permissions 控制。模式：
   ```vue
   const auth = useAuthStore()
   const canManage = computed(() =>
     auth.user?.permissions?.includes('*') || auth.user?.permissions?.includes('xxx:manage')
   )
   ```
4. **工作台首页**：必须根据用户权限展示不同的快捷入口和统计卡片。管理员看治理入口，普通用户看查询入口。

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
- 左侧固定侧边栏（已实现在 AppShell.vue）
- 顶部栏显示当前页面标题 + 用户信息
- 内容区使用卡片式布局，圆角 8px，柔和阴影
- 配色以蓝绿为主调，暖色点缀

### 设计令牌（CSS Variables）

已定义在 `src/style.css`，所有页面必须使用这些变量：

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
- 加载态：`el-skeleton`（首次加载）、`v-loading`（刷新）
- 空状态：`el-empty`
- 错误态：`el-result icon="error"`

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
1. [ ] 该页面的目标用户是谁？需要什么权限？
2. [ ] 路由是否配置了 `meta.permission`？
3. [ ] 页面内的操作按钮是否需要额外权限判断？
4. [ ] 是否使用了 `post-login-page` class？
5. [ ] 是否遵循了 page-header + content 的结构？
6. [ ] 加载态、空状态、错误态是否都处理了？
7. [ ] 是否使用了 CSS 变量而非硬编码颜色？
8. [ ] 图标是否来自 lucide-vue-next？
