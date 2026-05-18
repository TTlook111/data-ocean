import { createRouter, createWebHistory } from 'vue-router'
import LoginPage from '../views/login/LoginPage.vue'
import AdminHomeView from '../views/AdminHomeView.vue'
import DepartmentTree from '../views/admin/user/DepartmentTree.vue'
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
      path: '/admin',
      name: 'admin',
      component: AdminHomeView,
    },
    {
      path: '/admin/users',
      name: 'admin-users',
      component: UserList,
      meta: { permission: 'user:manage' },
    },
    {
      path: '/admin/roles',
      name: 'admin-roles',
      component: RoleList,
      meta: { permission: 'role:view' },
    },
    {
      path: '/admin/departments',
      name: 'admin-departments',
      component: DepartmentTree,
      meta: { permission: 'department:manage' },
    },
  ],
})

setupRouterGuards(router)

export default router
