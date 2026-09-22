import type { Router } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useIamS1Store } from '../stores/iamS1'

/** 标记是否已在本次会话中刷新过用户信息 */
let userInfoRefreshed = false

export function setupRouterGuards(router: Router) {
  router.beforeEach(async (to) => {
    const auth = useAuthStore()
    const iamS1 = useIamS1Store()
    const token = auth.token
    const user = auth.user as { passwordChanged?: boolean } | null

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
      // 六个后台业务域已改用 S1 能力摘要判定页面可见性，所以必须先加载能力摘要，
      // 再进任何放行分支——否则带旧权限的账号进页面后会读到空摘要，把有权限的卡片判成无权限。
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
