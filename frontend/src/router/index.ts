import { createRouter, createWebHistory } from 'vue-router'
import { setupRouterGuards } from './guards'

const LoginPage = () => import('../views/login/LoginPage.vue')
const AppShell = () => import('../components/AppShell.vue')
const AdminHomeView = () => import('../views/AdminHomeView.vue')
const ChangePassword = () => import('../views/profile/ChangePassword.vue')
const ProfileView = () => import('../views/profile/ProfileView.vue')
const QueryDatasourceView = () => import('../views/query/QueryDatasourceView.vue')
const DepartmentTree = () => import('../views/admin/user/DepartmentTree.vue')
const DatasourceList = () => import('../views/admin/datasource/DatasourceList.vue')
const DatasourceLifecycle = () => import('../views/admin/datasource/DatasourceLifecycle.vue')
const RoleList = () => import('../views/admin/user/RoleList.vue')
const UserList = () => import('../views/admin/user/UserList.vue')
const SyncTask = () => import('../views/admin/metadata/SyncTask.vue')
const SnapshotList = () => import('../views/admin/metadata/SnapshotList.vue')
const TableExplorer = () => import('../views/admin/metadata/TableExplorer.vue')
const SnapshotDiff = () => import('../views/admin/metadata/SnapshotDiff.vue')
const SyncSchedule = () => import('../views/admin/metadata/SyncSchedule.vue')
const QualityDashboard = () => import('../views/admin/governance/QualityDashboard.vue')
const IssueList = () => import('../views/admin/governance/IssueList.vue')
const StatusEditor = () => import('../views/admin/governance/StatusEditor.vue')
const SnapshotLifecycle = () => import('../views/admin/metadata/SnapshotLifecycle.vue')
const VersionHistory = () => import('../views/admin/metadata/VersionHistory.vue')
const SkillsEditor = () => import('../views/admin/knowledge/SkillsEditor.vue')
const VersionList = () => import('../views/admin/knowledge/VersionList.vue')
const ReviewPage = () => import('../views/admin/knowledge/ReviewPage.vue')
const KnowledgeDashboard = () => import('../views/admin/knowledge/KnowledgeDashboard.vue')
const PromptManager = () => import('../views/admin/prompt/PromptManager.vue')
const NotFound = () => import('../views/NotFound.vue')
const FieldTagManager = () => import('../views/admin/field/FieldTagManager.vue')
const ConfidenceDashboard = () => import('../views/admin/field/ConfidenceDashboard.vue')
const FeedbackReview = () => import('../views/admin/field/FeedbackReview.vue')
const AuditLogList = () => import('../views/admin/audit/AuditLogList.vue')
const SlowQueryList = () => import('../views/admin/audit/SlowQueryList.vue')
const LineageViewer = () => import('../views/admin/audit/LineageViewer.vue')
const LineageGraph = () => import('../views/admin/audit/LineageGraph.vue')
const GlossaryList = () => import('../views/admin/glossary/GlossaryList.vue')
const CatalogSearch = () => import('../views/admin/metadata/CatalogSearch.vue')
const AccessControl = () => import('../views/admin/permission/AccessControl.vue')
const PolicyEditor = () => import('../views/admin/permission/PolicyEditor.vue')
const ServiceHealth = () => import('../views/admin/system/ServiceHealth.vue')
const AiConfig = () => import('../views/admin/system/AiConfig.vue')
const OperationLogList = () => import('../views/admin/system/OperationLogList.vue')
const QueryGuide = () => import('../views/guide/QueryGuide.vue')
const AdminGuide = () => import('../views/guide/AdminGuide.vue')

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/query',
    },
    {
      path: '/guide/query',
      name: 'guide-query',
      component: QueryGuide,
      meta: { title: '快速入门 — 智能查询' },
    },
    {
      path: '/guide/admin',
      name: 'guide-admin',
      component: AdminGuide,
      meta: { title: '快速入门 — 管理员引导' },
    },
    {
      path: '/login',
      name: 'login',
      component: LoginPage,
    },
    {
      path: '/change-password',
      name: 'change-password',
      component: ChangePassword,
    },
    {
      path: '/profile',
      name: 'profile',
      component: ProfileView,
      meta: { title: '个人资料', section: '个人中心' },
    },
    {
      path: '/query',
      name: 'query',
      component: QueryDatasourceView,
      meta: { title: '智能问答', section: '智能查询' },
    },
    {
      path: '/admin',
      component: AppShell,
      children: [
        {
          path: '',
          name: 'admin',
          component: AdminHomeView,
          meta: { title: '工作台概览', section: '工作台' },
        },
        {
          path: 'users',
          name: 'admin-users',
          component: UserList,
          meta: { title: '用户管理', section: '权限与合规', permission: 'user:manage' },
        },
        {
          path: 'roles',
          name: 'admin-roles',
          component: RoleList,
          meta: { title: '角色管理', section: '权限与合规', permission: 'role:view' },
        },
        {
          path: 'departments',
          name: 'admin-departments',
          component: DepartmentTree,
          meta: { title: '部门管理', section: '权限与合规', permission: 'department:manage' },
        },
        {
          path: 'datasources',
          name: 'admin-datasources',
          component: DatasourceList,
          meta: { title: '数据源管理', section: '数据资产', permission: 'datasource:manage' },
        },
        {
          path: 'datasources/:id/lifecycle',
          name: 'admin-datasource-lifecycle',
          component: DatasourceLifecycle,
          meta: { title: '数据源上线流程', section: '数据资产', permission: 'datasource:manage' },
        },
        {
          path: 'metadata/sync',
          name: 'admin-metadata-sync',
          component: SyncTask,
          meta: { title: '同步任务', section: '数据资产', permission: 'metadata:manage' },
        },
        {
          path: 'metadata/snapshots',
          name: 'admin-metadata-snapshots',
          component: SnapshotList,
          meta: { title: '快照列表', section: '数据资产', permission: 'metadata:manage' },
        },
        {
          path: 'metadata/tables',
          name: 'admin-metadata-tables',
          component: TableExplorer,
          meta: { title: '表浏览器', section: '数据资产', permission: 'metadata:manage' },
        },
        {
          path: 'metadata/diff',
          name: 'admin-metadata-diff',
          component: SnapshotDiff,
          meta: { title: '快照差异', section: '数据资产', permission: 'metadata:manage' },
        },
        {
          path: 'metadata/schedule',
          name: 'admin-metadata-schedule',
          component: SyncSchedule,
          meta: { title: '同步调度', section: '数据资产', permission: 'metadata:manage' },
        },
        {
          path: 'metadata/catalog',
          name: 'admin-metadata-catalog',
          component: CatalogSearch,
          meta: { title: '目录搜索', section: '数据资产', permission: 'metadata:manage' },
        },
        {
          path: 'governance/quality',
          name: 'admin-governance-quality',
          component: QualityDashboard,
          meta: { title: '质量看板', section: '治理工作台', permission: 'metadata:manage' },
        },
        {
          path: 'governance/issues',
          name: 'admin-governance-issues',
          component: IssueList,
          meta: { title: '问题清单', section: '治理工作台', permission: 'metadata:manage' },
        },
        {
          path: 'governance/status',
          name: 'admin-governance-status',
          component: StatusEditor,
          meta: { title: '治理状态', section: '治理工作台', permission: 'metadata:manage' },
        },
        {
          path: 'metadata/lifecycle',
          name: 'admin-metadata-lifecycle',
          component: SnapshotLifecycle,
          meta: { title: '快照生命周期', section: '数据资产', permission: 'metadata:manage' },
        },
        {
          path: 'metadata/version-history',
          name: 'admin-metadata-version-history',
          component: VersionHistory,
          meta: { title: '版本历史', section: '数据资产', permission: 'metadata:manage' },
        },
        {
          path: 'knowledge',
          name: 'admin-knowledge',
          component: KnowledgeDashboard,
          meta: { title: '知识库总览', section: '语义资产', permission: 'knowledge:manage' },
        },
        {
          path: 'knowledge/editor/:id?',
          name: 'admin-knowledge-editor',
          component: SkillsEditor,
          meta: { title: 'Skills 编辑器', section: '语义资产', permission: 'knowledge:manage' },
        },
        {
          path: 'knowledge/versions/:id',
          name: 'admin-knowledge-versions',
          component: VersionList,
          meta: { title: '版本历史', section: '语义资产', permission: 'knowledge:manage' },
        },
        {
          path: 'knowledge/review',
          name: 'admin-knowledge-review',
          component: ReviewPage,
          meta: { title: '知识审核', section: '语义资产', permission: 'knowledge:manage' },
        },
        {
          path: 'prompts',
          name: 'admin-prompts',
          component: PromptManager,
          meta: { title: 'Prompt 管理', section: '语义资产', permission: 'prompt:manage' },
        },
        {
          path: 'field/tags',
          name: 'admin-field-tags',
          component: FieldTagManager,
          meta: { title: '字段标签', section: '治理工作台', permission: 'field-tag:manage' },
        },
        {
          path: 'field/confidence',
          name: 'admin-field-confidence',
          component: ConfidenceDashboard,
          meta: { title: '可信度看板', section: '治理工作台', permission: 'field-tag:manage' },
        },
        {
          path: 'field/feedback-review',
          name: 'admin-field-feedback-review',
          component: FeedbackReview,
          meta: { title: '反馈审核', section: '治理工作台', permission: 'field-tag:manage' },
        },
        {
          path: 'audit/logs',
          name: 'admin-audit-logs',
          component: AuditLogList,
          meta: { title: '审计日志', section: '权限与合规', permission: 'audit:view' },
        },
        {
          path: 'audit/slow-queries',
          name: 'admin-audit-slow',
          component: SlowQueryList,
          meta: { title: '慢查询', section: '系统运维', permission: 'audit:view' },
        },
        {
          path: 'audit/lineage',
          name: 'admin-audit-lineage',
          component: LineageViewer,
          meta: { title: '血缘查看', section: '权限与合规', permission: 'audit:view' },
        },
        {
          path: 'audit/lineage-graph',
          name: 'admin-audit-lineage-graph',
          component: LineageGraph,
          meta: { title: '血缘图谱', section: '权限与合规', permission: 'audit:view' },
        },
        {
          path: 'glossary/list',
          name: 'admin-glossary-list',
          component: GlossaryList,
          meta: { title: '术语管理', section: '语义资产', permission: 'metadata:manage' },
        },
        {
          path: 'permission/access',
          name: 'admin-permission-access',
          component: AccessControl,
          meta: { title: '访问控制', section: '权限与合规', permission: 'security:manage' },
        },
        {
          path: 'permission/policies',
          name: 'admin-permission-policies',
          component: PolicyEditor,
          meta: { title: '策略编辑器', section: '权限与合规', permission: 'security:manage' },
        },
        {
          path: 'system/health',
          name: 'admin-system-health',
          component: ServiceHealth,
          meta: { title: '服务健康', section: '系统运维', permission: '*' },
        },
        {
          path: 'system/operation-logs',
          name: 'admin-system-operation-logs',
          component: OperationLogList,
          meta: { title: '操作日志', section: '系统运维', permission: 'audit:view' },
        },
        {
          path: 'system/ai-config',
          name: 'admin-system-ai-config',
          component: AiConfig,
          meta: { title: 'AI 配置', section: '系统运维', permission: 'system:ai-config:view' },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: NotFound,
      meta: { title: '页面不存在' },
    },
  ],
})

setupRouterGuards(router)

export default router
