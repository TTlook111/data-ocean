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
  'system:ai-config:view',
  'system:ai-config:manage',
]

/** 标记是否已在本次会话中刷新过用户信息 */
let userInfoRefreshed = false

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

    if (to.path === '/admin' || to.path.startsWith('/admin/')) {
      if (!hasAdminAccess(user)) return '/query'
    }

    // 后台路由不使用 meta.permission，守卫故意不实现细粒度权限判断。
    // 按《实施任务清单》§4，meta.permission / anyPermissions 留给后续权限系统任务；
    // 本轮 hasAdminAccess 仍是唯一的后台入口门禁，页面内操作由组件自行按 permissions 控制。
    return true
  })
}
