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
const FieldTagManager = () => import('../views/admin/field/FieldTagManager.vue')
const ConfidenceDashboard = () => import('../views/admin/field/ConfidenceDashboard.vue')
const FeedbackReview = () => import('../views/admin/field/FeedbackReview.vue')
const AuditLogList = () => import('../views/admin/audit/AuditLogList.vue')
const SlowQueryList = () => import('../views/admin/audit/SlowQueryList.vue')
const LineageViewer = () => import('../views/admin/audit/LineageViewer.vue')

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/query',
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
          meta: { title: '用户管理', section: '治理管理', permission: 'user:manage' },
        },
        {
          path: 'roles',
          name: 'admin-roles',
          component: RoleList,
          meta: { title: '角色管理', section: '治理管理', permission: 'role:view' },
        },
        {
          path: 'departments',
          name: 'admin-departments',
          component: DepartmentTree,
          meta: { title: '部门管理', section: '治理管理', permission: 'department:manage' },
        },
        {
          path: 'datasources',
          name: 'admin-datasources',
          component: DatasourceList,
          meta: { title: '数据源管理', section: '治理管理', permission: 'datasource:manage' },
        },
        {
          path: 'metadata/sync',
          name: 'admin-metadata-sync',
          component: SyncTask,
          meta: { title: '同步任务', section: '元数据管理', permission: 'metadata:manage' },
        },
        {
          path: 'metadata/snapshots',
          name: 'admin-metadata-snapshots',
          component: SnapshotList,
          meta: { title: '快照列表', section: '元数据管理', permission: 'metadata:manage' },
        },
        {
          path: 'metadata/tables',
          name: 'admin-metadata-tables',
          component: TableExplorer,
          meta: { title: '表浏览器', section: '元数据管理', permission: 'metadata:manage' },
        },
        {
          path: 'metadata/diff',
          name: 'admin-metadata-diff',
          component: SnapshotDiff,
          meta: { title: '快照差异', section: '元数据管理', permission: 'metadata:manage' },
        },
        {
          path: 'metadata/schedule',
          name: 'admin-metadata-schedule',
          component: SyncSchedule,
          meta: { title: '同步调度', section: '元数据管理', permission: 'metadata:manage' },
        },
        {
          path: 'governance/quality',
          name: 'admin-governance-quality',
          component: QualityDashboard,
          meta: { title: '质量看板', section: '元数据治理', permission: 'metadata:manage' },
        },
        {
          path: 'governance/issues',
          name: 'admin-governance-issues',
          component: IssueList,
          meta: { title: '问题清单', section: '元数据治理', permission: 'metadata:manage' },
        },
        {
          path: 'governance/status',
          name: 'admin-governance-status',
          component: StatusEditor,
          meta: { title: '治理状态', section: '元数据治理', permission: 'metadata:manage' },
        },
        {
          path: 'metadata/lifecycle',
          name: 'admin-metadata-lifecycle',
          component: SnapshotLifecycle,
          meta: { title: '快照生命周期', section: '版本管理', permission: 'metadata:manage' },
        },
        {
          path: 'metadata/version-history',
          name: 'admin-metadata-version-history',
          component: VersionHistory,
          meta: { title: '版本历史', section: '版本管理', permission: 'metadata:manage' },
        },
        {
          path: 'knowledge',
          name: 'admin-knowledge',
          component: KnowledgeDashboard,
          meta: { title: '知识库总览', section: '知识库管理', permission: 'knowledge:manage' },
        },
        {
          path: 'knowledge/editor/:id?',
          name: 'admin-knowledge-editor',
          component: SkillsEditor,
          meta: { title: 'Skills 编辑器', section: '知识库管理', permission: 'knowledge:manage' },
        },
        {
          path: 'knowledge/versions/:id',
          name: 'admin-knowledge-versions',
          component: VersionList,
          meta: { title: '版本历史', section: '知识库管理', permission: 'knowledge:manage' },
        },
        {
          path: 'knowledge/review',
          name: 'admin-knowledge-review',
          component: ReviewPage,
          meta: { title: '知识审核', section: '知识库管理', permission: 'knowledge:manage' },
        },
        {
          path: 'field/tags',
          name: 'admin-field-tags',
          component: FieldTagManager,
          meta: { title: '字段标签', section: '字段治理', permission: 'field-tag:manage' },
        },
        {
          path: 'field/confidence',
          name: 'admin-field-confidence',
          component: ConfidenceDashboard,
          meta: { title: '可信度看板', section: '字段治理', permission: 'field-tag:manage' },
        },
        {
          path: 'field/feedback-review',
          name: 'admin-field-feedback-review',
          component: FeedbackReview,
          meta: { title: '反馈审核', section: '字段治理', permission: 'field-tag:manage' },
        },
        {
          path: 'audit/logs',
          name: 'admin-audit-logs',
          component: AuditLogList,
          meta: { title: '审计日志', section: '审计管理', permission: 'audit:view' },
        },
        {
          path: 'audit/slow-queries',
          name: 'admin-audit-slow',
          component: SlowQueryList,
          meta: { title: '慢查询', section: '审计管理', permission: 'audit:view' },
        },
        {
          path: 'audit/lineage',
          name: 'admin-audit-lineage',
          component: LineageViewer,
          meta: { title: '血缘查看', section: '审计管理', permission: 'audit:view' },
        },
      ],
    },
  ],
})

setupRouterGuards(router)

export default router
