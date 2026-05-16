# Implementation Plan: 后台治理管理端模块

**Branch**: `001-full-module-specs` | **Date**: 2026-05-16 | **Spec**: [spec.md](spec.md)

## Summary

后台治理管理端是面向管理员和数据分析师的统一管理界面，聚合数据源管理、元数据治理、skills.md 编辑、字段 Tag/可信度管理、反馈审核、审计日志等功能。与前端问答端共用同一 Vue 项目，通过路由 `/admin/*` 区分，按角色权限控制菜单和操作。

## Technical Context

**Language/Version**: TypeScript (Vue 3 + Vite)

**Primary Dependencies**: Vue 3, Vue Router, Pinia, Axios, Element Plus, ECharts (看板图表), @vueuse/core

**Testing**: Vitest + Vue Test Utils

**Target Platform**: Modern browsers (Chrome/Edge/Firefox latest 2 versions)

**Performance Goals**: 页面加载 < 3s, 列表查询 < 1s, 表格支持 1000+ 行流畅滚动

## Component Tree

```text
src/
├── views/admin/
│   ├── AdminLayout.vue                # 后台布局（侧边栏 + 顶栏 + 内容区）
│   ├── Dashboard.vue                  # 首页看板
│   ├── datasource/
│   │   ├── DataSourceList.vue         # 数据源列表
│   │   └── DataSourceForm.vue         # 数据源新增/编辑
│   ├── metadata/
│   │   ├── MetadataExplorer.vue       # 元数据浏览器（树形 + 详情）
│   │   └── GovernanceWorkbench.vue    # 治理工作台
│   ├── skills/
│   │   └── SkillsEditor.vue           # skills.md Markdown 编辑器
│   ├── prompt/
│   │   └── PromptManager.vue          # Prompt 模板管理
│   ├── fieldtag/
│   │   └── FieldTagManager.vue        # 字段 Tag 和可信度管理
│   ├── feedback/
│   │   └── FeedbackReview.vue         # 反馈审核队列
│   ├── audit/
│   │   └── AuditLogs.vue             # 审计日志查看
│   ├── user/
│   │   ├── UserManagement.vue         # 用户管理
│   │   └── RolePermission.vue         # 角色权限管理
│   └── NotFound.vue                   # 404 页面
├── components/admin/
│   ├── PageHeader.vue                 # 页面标题 + 面包屑 + 操作按钮
│   ├── DataTable.vue                  # 通用数据表格（筛选/分页/排序）
│   ├── StatusBadge.vue                # 状态标签（颜色映射）
│   ├── ConfirmDialog.vue              # 确认弹窗
│   ├── SearchForm.vue                 # 通用搜索表单
│   ├── SideMenu.vue                   # 侧边导航菜单
│   └── BreadCrumb.vue                # 面包屑导航
├── stores/
│   ├── auth.ts                        # 复用问答端 auth store
│   ├── menu.ts                        # 菜单状态（展开/折叠/当前选中）
│   └── admin.ts                       # 后台通用状态（全局 loading 等）
├── api/admin/
│   ├── datasource.ts                  # 数据源管理 API
│   ├── metadata.ts                    # 元数据 API
│   ├── governance.ts                  # 治理 API
│   ├── skills.ts                      # skills.md API
│   ├── fieldtag.ts                    # 字段 Tag API
│   ├── confidence.ts                  # 可信度 API
│   ├── feedback.ts                    # 反馈审核 API
│   ├── audit.ts                       # 审计日志 API
│   ├── user.ts                        # 用户管理 API
│   └── role.ts                        # 角色权限 API
├── router/
│   └── admin.ts                       # /admin/* 路由配置 + 权限守卫
└── directives/
    └── permission.ts                  # v-permission 指令（按钮级权限控制）
```

## Page-Permission Mapping

| Page | Route | Required Permission |
|------|-------|-------------------|
| Dashboard | /admin/dashboard | admin:view |
| DataSourceManagement | /admin/datasources | datasource:manage |
| MetadataExplorer | /admin/metadata | metadata:view |
| GovernanceWorkbench | /admin/governance | governance:manage |
| SkillsEditor | /admin/skills | skills:manage |
| PromptManager | /admin/prompts | prompt:manage |
| FieldTagManager | /admin/field-tags | field:manage |
| FeedbackReview | /admin/feedback | feedback:review |
| AuditLogs | /admin/audit | audit:view |
| UserManagement | /admin/users | user:manage |
| RolePermission | /admin/roles | role:manage |

## Implementation Phases

### Phase 1: 布局和路由框架

- 创建 AdminLayout（Element Plus Container 布局）
- 实现 SideMenu（根据用户权限动态生成菜单）
- 配置 /admin/* 路由和权限守卫
- 实现 v-permission 指令（按钮级权限控制）
- 创建通用组件：PageHeader, DataTable, StatusBadge, ConfirmDialog

### Phase 2: 数据源和元数据管理

- DataSourceList: 列表 + 新增/编辑/删除/连通性测试
- DataSourceForm: 表单验证 + 密码加密提示
- MetadataExplorer: 左侧树形（数据源→表→字段）+ 右侧详情面板
- GovernanceWorkbench: 问题清单 + 处理表单 + 状态流转

### Phase 3: 治理工具

- SkillsEditor: Markdown 编辑器（集成 md-editor-v3）+ 版本对比 + 审核发布
- PromptManager: Prompt 模板 CRUD + 变量占位符高亮
- FieldTagManager: 字段列表 + 批量打标 + 可信度查看/手动设置

### Phase 4: 审核和审计

- FeedbackReview: 审核队列 + 通过/驳回操作 + 关联字段展示
- AuditLogs: 多维度筛选 + 详情弹窗 + CSV 导出
- Dashboard: 质量分雷达图 + 查询趋势 + 待办事项统计

### Phase 5: 用户和权限

- UserManagement: 用户 CRUD + 角色分配 + 状态管理
- RolePermission: 角色 CRUD + 权限树勾选

## Key Design Decisions

1. **通用 DataTable 组件**: 封装 Element Plus Table + Pagination + 筛选表单，所有列表页复用，减少重复代码
2. **权限双重控制**: 路由守卫控制页面访问，v-permission 指令控制按钮显示
3. **菜单动态生成**: 根据用户 permissions 过滤菜单项，无权限的菜单不渲染
4. **Markdown 编辑器**: 使用 md-editor-v3（Vue 3 原生支持，轻量），不用 vditor（过重）
5. **API 层按模块拆分**: 每个管理模块独立 API 文件，便于维护和 mock
