# Research: 后台治理管理端模块

## 布局方案

**Decision**: Element Plus Container 布局（el-aside + el-header + el-main）

**Rationale**: Element Plus 内置布局组件，开箱即用。侧边栏可折叠，响应式适配。与项目已选定的 Element Plus 一致。

**Alternatives considered**:
- 自定义 CSS Grid 布局: 灵活但需要更多样式代码
- Ant Design Vue Layout: 需要引入额外 UI 库

## 权限控制方案

**Decision**: 路由守卫 + v-permission 自定义指令双重控制

**Rationale**: 路由守卫控制页面级访问（无权限直接跳转 403），v-permission 指令控制按钮级显示（无权限按钮不渲染）。双重保障，前端控制 UX，后端接口鉴权保证安全。

**路由守卫实现**:
```typescript
router.beforeEach((to, from, next) => {
  const authStore = useAuthStore()
  const requiredPermission = to.meta.permission
  if (requiredPermission && !authStore.hasPermission(requiredPermission)) {
    next('/403')
  } else {
    next()
  }
})
```

**v-permission 指令实现**:
```typescript
app.directive('permission', {
  mounted(el, binding) {
    const authStore = useAuthStore()
    if (!authStore.hasPermission(binding.value)) {
      el.parentNode?.removeChild(el)
    }
  }
})
```

## Markdown 编辑器选型

**Decision**: md-editor-v3

**Rationale**: 专为 Vue 3 设计，TypeScript 支持好，体积小（~200KB gzipped），支持预览、工具栏自定义、代码高亮。满足 skills.md 编辑需求。

**Alternatives considered**:
- vditor: 功能强大但体积大（~500KB），API 复杂
- @toast-ui/editor: Vue 3 支持不够原生，需要 wrapper
- Monaco Editor: 过重，适合 IDE 场景

## 通用 DataTable 组件设计

**Decision**: 封装 Element Plus Table + Pagination，通过 props 配置列定义、筛选条件、分页参数

**Rationale**: 后台 11 个页面中有 8 个需要数据表格，统一封装减少 60%+ 重复代码。

**Props 设计**:
```typescript
interface DataTableProps {
  columns: ColumnConfig[]       // 列定义
  fetchApi: (params) => Promise // 数据获取函数
  searchFields?: SearchField[]  // 搜索表单字段
  defaultPageSize?: number      // 默认分页大小
  rowKey?: string               // 行唯一标识
  selectable?: boolean          // 是否可多选
}
```

## 状态管理策略

**Decision**: 后台管理端复用 auth store，新增 menu store 管理侧边栏状态。不为每个管理页面创建独立 store。

**Rationale**: 管理页面大多是简单的 CRUD 列表，数据生命周期仅限于当前页面，使用组件内 ref/reactive 即可。只有跨页面共享的状态（登录态、菜单状态）才放入 store。

**Alternatives considered**:
- 每个模块一个 store: 过度设计，增加复杂度
- 全局单一 store: 职责不清，难以维护

## Dashboard 图表方案

**Decision**: 复用 ECharts（与问答端共享），Dashboard 使用 3-4 个图表组件

**图表清单**:
- 质量分雷达图: 各数据源的元数据质量评分
- 查询趋势折线图: 近 7/30 天查询量趋势
- 数据源使用饼图: 各数据源查询占比
- 待办统计卡片: 待审核反馈数、待处理问题数、慢查询数
