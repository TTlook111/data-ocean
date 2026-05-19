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
import { setupRouterGuards } from './guards'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/login',
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
      path: '/',
      component: AppShell,
      children: [
        {
          path: 'profile',
          name: 'profile',
          component: ProfileView,
          meta: { title: '个人资料', section: '个人中心' },
        },
        {
          path: 'query',
          name: 'query',
          component: QueryDatasourceView,
          meta: { title: '问答端数据源', section: '智能查询' },
        },
        {
          path: 'admin',
          name: 'admin',
          component: AdminHomeView,
          meta: { title: '工作台概览', section: '工作台' },
        },
        {
          path: 'admin/users',
          name: 'admin-users',
          component: UserList,
          meta: { title: '用户管理', section: '治理管理', permission: 'user:manage' },
        },
        {
          path: 'admin/roles',
          name: 'admin-roles',
          component: RoleList,
          meta: { title: '角色管理', section: '治理管理', permission: 'role:view' },
        },
        {
          path: 'admin/departments',
          name: 'admin-departments',
          component: DepartmentTree,
          meta: { title: '部门管理', section: '治理管理', permission: 'department:manage' },
        },
        {
          path: 'admin/datasources',
          name: 'admin-datasources',
          component: DatasourceList,
          meta: { title: '数据源管理', section: '治理管理', permission: 'datasource:manage' },
        },
      ],
    },
  ],
})

setupRouterGuards(router)

export default router
