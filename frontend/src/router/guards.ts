import type { Router } from 'vue-router'

const adminPermissions = [
  'admin:view',
  'datasource:manage',
  'metadata:manage',
  'skills:manage',
  'prompt:manage',
  'field:manage',
  'feedback:review',
  'audit:view',
  'user:manage',
  'role:manage',
  'role:view',
  'department:manage',
  'knowledge:manage',
]

function hasPermission(user: { permissions?: string[] } | null, permission: string) {
  return Boolean(user?.permissions?.includes('*') || user?.permissions?.includes(permission))
}

function hasAdminAccess(user: { permissions?: string[] } | null) {
  return Boolean(user?.permissions?.includes('*') || adminPermissions.some((permission) => user?.permissions?.includes(permission)))
}

export function setupRouterGuards(router: Router) {
  router.beforeEach((to) => {
    const token = localStorage.getItem('dataocean_token')
    const user = JSON.parse(localStorage.getItem('dataocean_user') || 'null') as {
      passwordChanged?: boolean
      permissions?: string[]
    } | null

    if (to.path === '/login') {
      return token ? '/query' : true
    }

    if (to.path !== '/login' && to.path !== '/change-password' && !token) {
      return '/login'
    }

    if (token && user && user.passwordChanged === false && to.path !== '/change-password') {
      return '/change-password?forced=1'
    }

    if (to.path === '/') {
      return token ? '/query' : true
    }

    if (to.path === '/admin' && !hasAdminAccess(user)) {
      return '/query'
    }

    const requiredPermission = to.meta.permission as string | undefined
    if (requiredPermission && !hasPermission(user, requiredPermission)) {
      return to.path.startsWith('/admin') ? '/query' : '/admin'
    }

    return true
  })
}
