# Data Model: 后台治理管理端模块

后台管理端是 UI 聚合层，不定义独立数据库表。它消费其他模块的 API 和数据模型。

## Frontend State Models (TypeScript)

### Menu State

```typescript
interface MenuState {
  collapsed: boolean
  activeMenu: string
  openKeys: string[]
  hasAdminEntry: boolean
}

interface MenuItem {
  key: string
  label: string
  icon: string
  route: string
  permission: string
  children?: MenuItem[]
}
```

### Menu Configuration

```typescript
const adminMenus: MenuItem[] = [
  { key: 'dashboard', label: '首页看板', icon: 'Odometer', route: '/admin/dashboard', permission: 'admin:view' },
  { key: 'datasource', label: '数据源管理', icon: 'Connection', route: '/admin/datasources', permission: 'datasource:manage' },
  { key: 'metadata', label: '元数据浏览', icon: 'Grid', route: '/admin/metadata', permission: 'metadata:view' },
  { key: 'governance', label: '治理工作台', icon: 'SetUp', route: '/admin/governance', permission: 'governance:manage' },
  { key: 'skills', label: 'Skills 编辑', icon: 'Document', route: '/admin/skills', permission: 'skills:manage' },
  { key: 'prompts', label: 'Prompt 管理', icon: 'ChatDotRound', route: '/admin/prompts', permission: 'prompt:manage' },
  { key: 'fieldtags', label: '字段标签', icon: 'PriceTag', route: '/admin/field-tags', permission: 'field:manage' },
  { key: 'feedback', label: '反馈审核', icon: 'ChatLineSquare', route: '/admin/feedback', permission: 'feedback:review' },
  { key: 'audit', label: '审计日志', icon: 'Notebook', route: '/admin/audit', permission: 'audit:view' },
  { key: 'users', label: '用户管理', icon: 'User', route: '/admin/users', permission: 'user:manage' },
  { key: 'roles', label: '角色权限', icon: 'Lock', route: '/admin/roles', permission: 'role:manage' },
]
```

右上角用户菜单中的“后台管理”入口根据当前用户权限动态显示。判定规则：用户拥有 `*`，或至少拥有一个后台功能权限（如 `admin:view`、`datasource:manage`、`metadata:manage`、`skills:manage`、`prompt:manage`、`field:manage`、`feedback:review`、`audit:view`、`user:manage`、`role:manage`）时展示；否则普通用户只看到个人资料、修改密码和退出登录。

### DataTable Generic Types

```typescript
interface ColumnConfig {
  prop: string
  label: string
  width?: number
  sortable?: boolean
  formatter?: (row: any) => string
  slot?: string  // 自定义插槽名
}

interface SearchField {
  prop: string
  label: string
  type: 'input' | 'select' | 'date-range' | 'number'
  options?: { label: string; value: any }[]
  placeholder?: string
}

interface PageResult<T> {
  records: T[]
  total: number
  page: number
  pageSize: number
}
```

### Dashboard Statistics

```typescript
interface DashboardStats {
  totalDatasources: number
  totalQueries: number
  todayQueries: number
  successRate: number
  pendingFeedbacks: number
  pendingIssues: number
  slowQueries: number
}

interface QualityScore {
  datasourceId: number
  datasourceName: string
  score: number  // 0-100
  dimensions: {
    completeness: number
    accuracy: number
    consistency: number
    timeliness: number
  }
}
```

## Consumed API Modules

后台管理端消费以下模块的 API:

| Module | APIs Consumed |
|--------|--------------|
| 001-user-module | /api/admin/users/*, /api/admin/roles/*, /api/auth/* |
| 003-datasource | /api/admin/datasources/* |
| 005-metadata | /api/admin/metadata/*, /api/admin/governance/* |
| 007-skills | /api/admin/skills/* |
| 008-prompt | /api/admin/prompt-templates/* |
| 010-field-tag-confidence | /api/admin/field-tags/*, /api/admin/field-confidence/*, /api/admin/feedback-reviews/* |
| 011-lineage-audit | /api/admin/audit-logs/*, /api/admin/lineage/* |
