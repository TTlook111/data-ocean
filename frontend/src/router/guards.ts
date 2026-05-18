import type { Router } from 'vue-router'

export function setupRouterGuards(router: Router) {
  router.beforeEach((to) => {
    const token = localStorage.getItem('dataocean_token')
    const user = JSON.parse(localStorage.getItem('dataocean_user') || 'null') as { permissions?: string[] } | null

    if (to.path.startsWith('/admin') && !token) {
      return '/login'
    }

    if (to.path === '/login' && token) {
      return '/admin/users'
    }

    const requiredPermission = to.meta.permission as string | undefined
    if (requiredPermission && !user?.permissions?.includes('*') && !user?.permissions?.includes(requiredPermission)) {
      return '/admin'
    }

    return true
  })
}
