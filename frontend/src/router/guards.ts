import type { Router } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const adminPermissions = [
  'admin:view',
  'datasource:manage',
  'metadata:manage',
  'skills:manage',
  'prompt:manage',
  'field:manage',
  'field-tag:manage',
  'feedback:review',
  'audit:view',
  'user:manage',
  'role:manage',
  'role:view',
  'department:manage',
  'knowledge:manage',
  'security:manage',
]

/** 标记是否已在本次会话中刷新过用户信息 */
let userInfoRefreshed = false

function hasPermission(user: { permissions?: string[] } | null, permission: string) {
  return Boolean(user?.permissions?.includes('*') || user?.permissions?.includes(permission))
}

function hasAdminAccess(user: { permissions?: string[] } | null) {
  return Boolean(user?.permissions?.includes('*') || adminPermissions.some((permission) => user?.permissions?.includes(permission)))
}

export function setupRouterGuards(router: Router) {
  router.beforeEach(async (to) => {
    const auth = useAuthStore()
    const token = auth.token
    const user = auth.user as { passwordChanged?: boolean; permissions?: string[] } | null

    if (to.path === '/login') {
      return token ? '/query' : true
    }

    if (to.path !== '/login' && to.path !== '/change-password' && !token) {
      return '/login'
    }

    // 启动时静默刷新用户权限（仅首次导航时执行，失败不阻塞）
    if (token && !userInfoRefreshed) {
      userInfoRefreshed = true
      try {
        await auth.fetchUserInfo()
      } catch {
        // 刷新失败降级使用缓存数据，不阻塞导航
      }
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
      return '/query'
    }

    return true
  })
}
