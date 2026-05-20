import { createRouter, createWebHistory } from 'vue-router'
import LoginPage from '../views/login/LoginPage.vue'
import AppShell from '../components/AppShell.vue'
import AdminHomeView from '../views/AdminHomeView.vue'
import ChangePassword from '../views/profile/ChangePassword.vue'
import ProfileView from '../views/profile/ProfileView.vue'
import QueryDatasourceView from '../views/query/QueryDatasourceView.vue'
import DepartmentTree from '../views/admin/user/DepartmentTree.vue'
import DatasourceList from '../views/admin/datasource/DatasourceList.vue'
import RoleList from '../views/admin/user/RoleList.vue'
import UserList from '../views/admin/user/UserList.vue'
import SyncTask from '../views/admin/metadata/SyncTask.vue'
import SnapshotList from '../views/admin/metadata/SnapshotList.vue'
import TableExplorer from '../views/admin/metadata/TableExplorer.vue'
import SnapshotDiff from '../views/admin/metadata/SnapshotDiff.vue'
import SyncSchedule from '../views/admin/metadata/SyncSchedule.vue'
import QualityDashboard from '../views/admin/governance/QualityDashboard.vue'
import IssueList from '../views/admin/governance/IssueList.vue'
import StatusEditor from '../views/admin/governance/StatusEditor.vue'
import { setupRouterGuards } from './guards'

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
      ],
    },
  ],
})

setupRouterGuards(router)

export default router
