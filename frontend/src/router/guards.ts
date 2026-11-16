import type { Router } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useIamS1Store } from '../stores/iamS1'
import { useAdminContextStore } from '../stores/adminContext'
import { resolveAdminEntry } from '../utils/adminNavigation'

export function setupRouterGuards(router: Router) {
  let refreshedToken = ''
  let navigationSequence = 0
  router.beforeEach(async (to) => {
    const navigation = ++navigationSequence
    const auth = useAuthStore()
    const iamS1 = useIamS1Store()
    const token = auth.token
    const defaultEntry = to.path === '/login' || to.path === '/'
      // 根路由在 beforeEach 前已 redirect 到 /query，不能据目标路径误认为明确问数导航。
      || (to.path === '/query' && to.redirectedFrom?.path === '/')
    const adminDefaultEntry = to.path === '/admin' || (to.path === '/admin/workbench'
      && ['/admin', '/admin/'].includes(to.redirectedFrom?.path || ''))

    if (to.path === '/login' && !token) return true

    if (to.path !== '/login' && to.path !== '/change-password' && !token) {
      return '/login'
    }

    // 启动时静默刷新用户信息（仅首次导航时执行，失败不阻塞）
    if (token && refreshedToken !== token) {
      refreshedToken = token
      try {
        await auth.fetchUserInfo()
      } catch {
        // 刷新失败降级使用缓存数据，不阻塞导航
      }
    }

    // 旧请求不能用重定向覆盖另一次已开始的导航（包括新账号主动选择的页面）。
    if (navigation !== navigationSequence) return false
    if (auth.token !== token) return '/login'

    const user = auth.currentUser ?? auth.user
    if (token && user?.passwordChanged === false && to.path !== '/change-password') {
      return '/change-password?forced=1'
    }

    if (defaultEntry) {
      // 默认落点核对最新服务端能力；不能把菜单读取失败时的可见性降级当成有效入口。
      try { await iamS1.load(true) } catch { /* 读取失败由下面的明确兜底处理。 */ }
      if (navigation !== navigationSequence) return false
      if (auth.token !== token) return '/login'
      if (!iamS1.loaded || iamS1.errorMessage) return '/profile'
      if (iamS1.queryUse) return to.path === '/query' ? true : '/query'
      const context = useAdminContextStore()
      return resolveAdminEntry(iamS1, {
        datasourceId: context.datasourceId,
        snapshotId: context.currentSnapshot?.id,
      }) ?? '/profile'
    }

    if (to.path === '/admin' || to.path.startsWith('/admin/')) {
      // 六个后台业务域已改用 S1 能力摘要判定页面可见性，所以必须先加载能力摘要，
      // 再进任何放行分支——否则带旧权限的账号进页面后会读到空摘要，把有权限的卡片判成无权限。
      try {
        await iamS1.load(adminDefaultEntry)
      } catch {
        // 读取失败时按“无能力”处理，不静默回退旧权限
      }
      if (navigation !== navigationSequence) return false
      if (auth.token !== token) return '/login'
      if (iamS1.hasAnyAdminCapability) {
        if (adminDefaultEntry) {
          const context = useAdminContextStore()
          const entry = resolveAdminEntry(iamS1, {
            datasourceId: context.datasourceId,
            snapshotId: context.currentSnapshot?.id,
          })
          if (!entry) return '/profile'
          if (entry.path !== to.path) return entry
        }
        return true
      }
      return iamS1.loaded && !iamS1.errorMessage && iamS1.queryUse ? '/query' : '/profile'
    }

    // S1 页面不做前端细粒度拦截：页面按能力摘要显示明确中文提示，后端始终强制校验。
    return true
  })
}
