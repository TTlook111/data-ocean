import type { Router } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useIamS1Store } from '../stores/iamS1'

/**
 * 旧运行环境的后台入口权限码。
 *
 * 注意：这些旧权限码只用于切换前的旧后台工作区门禁，不得用于给 IAM-SIMPLE-1 页面授权。
 * S1 页面的可见性一律来自 Java 返回的 S1 能力摘要（useIamS1Store），
 * 并且后端会对每个接口独立校验，前端隐藏按钮不构成授权。
 */
const legacyAdminPermissions = [
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

function hasLegacyAdminAccess(user: { permissions?: string[] } | null) {
  return Boolean(
    user?.permissions?.includes('*') ||
      legacyAdminPermissions.some((permission) => user?.permissions?.includes(permission)),
  )
}

export function setupRouterGuards(router: Router) {
  router.beforeEach(async (to) => {
    const auth = useAuthStore()
    const iamS1 = useIamS1Store()
    const token = auth.token
    const user = auth.user as { passwordChanged?: boolean; permissions?: string[] } | null

    if (to.path === '/login') {
      return token ? '/query' : true
    }

    if (to.path !== '/login' && to.path !== '/change-password' && !token) {
      return '/login'
    }

    // 启动时静默刷新用户信息（仅首次导航时执行，失败不阻塞）
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
      // 先按旧环境权限放行，再补一次 S1 能力判定：只有 S1 新绑定的账号也能进入后台，
      // 进入具体工作区后由页面和能力摘要给出明确中文提示，后端仍会独立校验每个接口。
      if (hasLegacyAdminAccess(user)) return true
      try {
        await iamS1.load()
      } catch {
        // 读取失败时按“无能力”处理，不静默回退旧权限
      }
      if (iamS1.hasAnyAdminCapability) return true
      return '/query'
    }

    // S1 页面不做前端细粒度拦截：页面按能力摘要显示明确中文提示，后端始终强制校验。
    return true
  })
}
