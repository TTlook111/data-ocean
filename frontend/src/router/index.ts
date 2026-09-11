import { createRouter, createWebHistory } from 'vue-router'
import { setupRouterGuards } from './guards'

const LoginPage = () => import('../views/login/LoginPage.vue')
const AdminShell = () => import('../components/admin/AdminShell.vue')
const AdminHomeView = () => import('../views/AdminHomeView.vue')
const DataSourcesView = () => import('../views/admin/datasource/DataSourcesView.vue')
const DataSourceDetailView = () => import('../views/admin/datasource/DataSourceDetailView.vue')
const CollectionsView = () => import('../views/admin/metadata/CollectionsView.vue')
const AssetsView = () => import('../views/admin/assets/AssetsView.vue')
const ReleasesView = () => import('../views/admin/releases/ReleasesView.vue')
const SnapshotDiffView = () => import('../views/admin/releases/SnapshotDiffView.vue')
const AssetEntityDetailView = () => import('../views/admin/assets/AssetEntityDetailView.vue')
const SnapshotDetailView = () => import('../views/admin/releases/SnapshotDetailView.vue')
const AccessApprovalView = () => import('../views/admin/permission/AccessApprovalView.vue')
const OrganizationView = () => import('../views/admin/user/OrganizationView.vue')
const QueryAnalysisView = () => import('../views/admin/audit/QueryAnalysisView.vue')
const ChangePassword = () => import('../views/profile/ChangePassword.vue')
const ProfileView = () => import('../views/profile/ProfileView.vue')
const QueryDatasourceView = () => import('../views/query/QueryDatasourceView.vue')
const QualityDashboard = () => import('../views/admin/governance/QualityDashboard.vue')
const IssueList = () => import('../views/admin/governance/IssueList.vue')
const StatusEditor = () => import('../views/admin/governance/StatusEditor.vue')
const SkillsEditor = () => import('../views/admin/knowledge/SkillsEditor.vue')
const VersionList = () => import('../views/admin/knowledge/VersionList.vue')
const KnowledgeDashboard = () => import('../views/admin/knowledge/KnowledgeDashboard.vue')
const PromptManager = () => import('../views/admin/prompt/PromptManager.vue')
const NotFound = () => import('../views/NotFound.vue')
const FieldTagManager = () => import('../views/admin/field/FieldTagManager.vue')
const TableExplorer = () => import('../views/admin/metadata/TableExplorer.vue')
const ConfidenceDashboard = () => import('../views/admin/field/ConfidenceDashboard.vue')
const FeedbackReview = () => import('../views/admin/field/FeedbackReview.vue')
const ReviewPage = () => import('../views/admin/knowledge/ReviewPage.vue')
const DataLineage = () => import('../views/admin/audit/DataLineage.vue')
const GlossaryList = () => import('../views/admin/glossary/GlossaryList.vue')
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
      component: AdminShell,
      children: [
        {
          path: '',
          redirect: '/admin/workbench',
        },
        {
          path: 'workbench',
          name: 'admin-workbench',
          component: AdminHomeView,
          meta: { title: '工作台', domainKey: 'workbench', workspaceKey: 'workbench', contextMode: 'none' },
        },
        {
          path: 'data-sources',
          name: 'admin-data-sources',
          component: DataSourcesView,
          meta: { title: '数据源', domainKey: 'data-entry', workspaceKey: 'data-sources', contextMode: 'none' },
        },
        {
          path: 'data-sources/:id',
          name: 'admin-data-source-detail',
          component: DataSourceDetailView,
          meta: { title: '数据源详情', domainKey: 'data-entry', workspaceKey: 'data-sources', contextMode: 'locked-resource', breadcrumbParent: '/admin/data-sources' },
        },
        {
          path: 'collections',
          name: 'admin-collections',
          component: CollectionsView,
          meta: { title: '采集任务', domainKey: 'data-entry', workspaceKey: 'collections', contextMode: 'datasource' },
        },
        {
          path: 'assets',
          name: 'admin-assets',
          component: AssetsView,
          meta: { title: '资产目录', domainKey: 'data-assets', workspaceKey: 'assets', contextMode: 'datasource' },
        },
        {
          path: 'assets/entities/:entityId',
          name: 'admin-asset-detail',
          component: AssetEntityDetailView,
          meta: { title: '资产详情', domainKey: 'data-assets', workspaceKey: 'assets', contextMode: 'locked-resource', breadcrumbParent: '/admin/assets' },
        },
        {
          path: 'releases',
          name: 'admin-releases',
          component: ReleasesView,
          meta: { title: '版本发布', domainKey: 'data-assets', workspaceKey: 'releases', contextMode: 'datasource' },
        },
        {
          path: 'releases/snapshots/:snapshotId',
          name: 'admin-snapshot-detail',
          component: SnapshotDetailView,
          meta: { title: '快照详情', domainKey: 'data-assets', workspaceKey: 'releases', contextMode: 'locked-resource', breadcrumbParent: '/admin/releases' },
        },
        {
          path: 'releases/snapshots/:snapshotId/diff/:compareId',
          name: 'admin-snapshot-diff',
          component: SnapshotDiffView,
          meta: { title: '快照差异', domainKey: 'data-assets', workspaceKey: 'releases', contextMode: 'locked-resource', breadcrumbParent: '/admin/releases' },
        },
        {
          path: 'governance',
          name: 'admin-governance',
          component: QualityDashboard,
          meta: { title: '治理总览', domainKey: 'governance', workspaceKey: 'governance', contextMode: 'datasource-snapshot' },
        },
        {
          path: 'governance/issues',
          name: 'admin-governance-issues-new',
          component: IssueList,
          meta: { title: '问题中心', domainKey: 'governance', workspaceKey: 'governance-issues', contextMode: 'datasource-snapshot' },
        },
        {
          path: 'governance/rules',
          name: 'admin-governance-rules',
          component: StatusEditor,
          meta: { title: '规则与状态', domainKey: 'governance', workspaceKey: 'governance-rules', contextMode: 'datasource-snapshot' },
        },
        {
          path: 'governance/fields',
          name: 'admin-governance-fields',
          component: FieldTagManager,
          meta: { title: '字段治理', domainKey: 'governance', workspaceKey: 'governance-fields', contextMode: 'datasource-snapshot' },
        },
        {
          path: 'semantics/glossaries',
          name: 'admin-semantic-glossaries',
          component: GlossaryList,
          meta: { title: '业务术语', domainKey: 'semantics', workspaceKey: 'glossaries', contextMode: 'none' },
        },
        {
          path: 'semantics/knowledge',
          name: 'admin-semantic-knowledge',
          component: KnowledgeDashboard,
          meta: { title: '语义知识', domainKey: 'semantics', workspaceKey: 'knowledge', contextMode: 'datasource' },
        },
        {
          path: 'semantics/knowledge/new',
          name: 'admin-semantic-knowledge-new',
          component: SkillsEditor,
          meta: { title: '新建知识文档', domainKey: 'semantics', workspaceKey: 'knowledge', contextMode: 'datasource', breadcrumbParent: '/admin/semantics/knowledge' },
        },
        {
          path: 'semantics/knowledge/:id/versions',
          name: 'admin-semantic-knowledge-versions',
          component: VersionList,
          meta: { title: '知识版本历史', domainKey: 'semantics', workspaceKey: 'knowledge', contextMode: 'locked-resource', breadcrumbParent: '/admin/semantics/knowledge' },
        },
        {
          path: 'semantics/knowledge/:id',
          name: 'admin-semantic-knowledge-detail',
          component: SkillsEditor,
          meta: { title: '知识文档详情', domainKey: 'semantics', workspaceKey: 'knowledge', contextMode: 'locked-resource', breadcrumbParent: '/admin/semantics/knowledge' },
        },
        {
          path: 'semantics/prompts',
          name: 'admin-semantic-prompts',
          component: PromptManager,
          meta: { title: 'Prompt 策略', domainKey: 'semantics', workspaceKey: 'prompts', contextMode: 'none' },
        },
        {
          path: 'access',
          name: 'admin-access',
          component: AccessControl,
          meta: { title: '授权管理', domainKey: 'access', workspaceKey: 'access', contextMode: 'datasource' },
        },
        {
          path: 'access/approvals',
          name: 'admin-access-approvals',
          component: AccessApprovalView,
          meta: { title: '访问审批', domainKey: 'access', workspaceKey: 'access-approvals', contextMode: 'none' },
        },
        {
          path: 'access/organization',
          name: 'admin-access-organization',
          component: OrganizationView,
          meta: { title: '组织与角色', domainKey: 'access', workspaceKey: 'organization', contextMode: 'none' },
        },
        {
          path: 'operations/queries',
          name: 'admin-operations-queries',
          component: QueryAnalysisView,
          meta: { title: '查询分析', domainKey: 'operations', workspaceKey: 'queries', contextMode: 'none' },
        },
        {
          path: 'operations/lineage',
          name: 'admin-operations-lineage',
          component: DataLineage,
          meta: { title: '数据血缘', domainKey: 'operations', workspaceKey: 'lineage', contextMode: 'datasource' },
        },
        {
          path: 'platform/runtime',
          name: 'admin-platform-runtime',
          component: ServiceHealth,
          meta: { title: '运行监控', domainKey: 'operations', workspaceKey: 'runtime', contextMode: 'none' },
        },
        {
          path: 'platform/operation-logs',
          name: 'admin-platform-operation-logs',
          component: OperationLogList,
          meta: { title: '操作日志', domainKey: 'operations', workspaceKey: 'operation-logs', contextMode: 'none' },
        },
        {
          path: 'platform/ai',
          name: 'admin-platform-ai',
          component: AiConfig,
          meta: { title: 'AI 配置', domainKey: 'operations', workspaceKey: 'ai', contextMode: 'none' },
        },
        { path: 'datasources', redirect: (to) => ({ path: '/admin/data-sources', query: to.query }) },
        { path: 'datasources/:id/lifecycle', redirect: (to) => ({ path: '/admin/data-sources/' + to.params.id, query: to.query }) },
        { path: 'metadata/sync', redirect: (to) => ({ path: '/admin/collections', query: to.query }) },
        { path: 'metadata/schedule', redirect: (to) => ({ path: '/admin/collections', query: { ...to.query, tab: 'schedule' } }) },
        { path: 'metadata/catalog', redirect: (to) => ({ path: '/admin/assets', query: to.query }) },
        {
          path: 'metadata/tables',
          name: 'admin-metadata-tables',
          component: TableExplorer,
          meta: { title: '表浏览器', domainKey: 'data-assets', workspaceKey: 'assets', contextMode: 'datasource-snapshot' },
        },
        { path: 'metadata/lifecycle', redirect: (to) => ({ path: '/admin/releases', query: to.query }) },
        { path: 'metadata/snapshots', redirect: (to) => ({ path: '/admin/releases', query: to.query }) },
        { path: 'metadata/version-history', redirect: (to) => ({ path: '/admin/releases', query: { ...to.query, tab: 'history' } }) },
        {
          path: 'metadata/diff',
          redirect: (to) => {
            const oldId = Number(to.query.oldId)
            const newId = Number(to.query.newId)
            if (oldId && newId) {
              const { oldId: _oldId, newId: _newId, ...query } = to.query
              return {
                name: 'admin-snapshot-diff',
                params: { snapshotId: String(oldId), compareId: String(newId) },
                query,
              }
            }
            return { path: '/admin/releases', query: { ...to.query, tab: 'diff' } }
          },
        },
        { path: 'governance/quality', redirect: (to) => ({ path: '/admin/governance', query: to.query }) },
        { path: 'governance/status', redirect: (to) => ({ path: '/admin/governance/rules', query: { ...to.query, tab: 'status' } }) },
        { path: 'field/tags', redirect: (to) => ({ path: '/admin/governance/fields', query: { ...to.query, tab: 'tags' } }) },
        {
          path: 'field/confidence',
          name: 'admin-field-confidence',
          component: ConfidenceDashboard,
          meta: { title: '字段可信度', domainKey: 'governance', workspaceKey: 'governance-fields', contextMode: 'datasource-snapshot' },
        },
        {
          path: 'field/feedback-review',
          name: 'admin-field-feedback-review',
          component: FeedbackReview,
          meta: { title: '反馈审核', domainKey: 'governance', workspaceKey: 'governance-fields', contextMode: 'datasource-snapshot' },
        },
        { path: 'glossary/list', redirect: (to) => ({ path: '/admin/semantics/glossaries', query: to.query }) },
        { path: 'knowledge', redirect: (to) => ({ path: '/admin/semantics/knowledge', query: to.query }) },
        { path: 'knowledge/editor/:id?', redirect: (to) => ({ path: to.params.id ? '/admin/semantics/knowledge/' + to.params.id : '/admin/semantics/knowledge/new', query: to.query }) },
        { path: 'knowledge/versions/:id', redirect: (to) => ({ path: '/admin/semantics/knowledge/' + to.params.id + '/versions', query: to.query }) },
        {
          path: 'knowledge/review',
          name: 'admin-knowledge-review',
          component: ReviewPage,
          meta: { title: '知识审核', domainKey: 'semantics', workspaceKey: 'knowledge', contextMode: 'datasource' },
        },
        { path: 'prompts', redirect: (to) => ({ path: '/admin/semantics/prompts', query: to.query }) },
        { path: 'permission/access', redirect: (to) => ({ path: '/admin/access', query: { ...to.query, tab: 'grants' } }) },
        {
          path: 'permission/policies',
          name: 'admin-permission-policies',
          component: PolicyEditor,
          meta: { title: '策略编辑器', domainKey: 'access', workspaceKey: 'access', contextMode: 'datasource' },
        },
        { path: 'users', redirect: (to) => ({ path: '/admin/access/organization', query: { ...to.query, tab: 'users' } }) },
        { path: 'roles', redirect: (to) => ({ path: '/admin/access/organization', query: { ...to.query, tab: 'roles' } }) },
        { path: 'departments', redirect: (to) => ({ path: '/admin/access/organization', query: { ...to.query, tab: 'departments' } }) },
        { path: 'audit/logs', redirect: (to) => ({ path: '/admin/operations/queries', query: { ...to.query, tab: 'audit' } }) },
        { path: 'audit/slow-queries', redirect: (to) => ({ path: '/admin/operations/queries', query: { ...to.query, tab: 'performance' } }) },
        { path: 'audit/data-lineage', redirect: (to) => ({ path: '/admin/operations/lineage', query: to.query }) },
        { path: 'audit/lineage', redirect: (to) => ({ path: '/admin/operations/lineage', query: to.query }) },
        { path: 'audit/lineage-graph', redirect: (to) => ({ path: '/admin/operations/lineage', query: to.query }) },
        { path: 'system/health', redirect: (to) => ({ path: '/admin/platform/runtime', query: to.query }) },
        { path: 'system/operation-logs', redirect: (to) => ({ path: '/admin/platform/operation-logs', query: to.query }) },
        { path: 'system/ai-config', redirect: (to) => ({ path: '/admin/platform/ai', query: to.query }) },
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
