import type { Router } from 'vue-router'

export function setupRouterGuards(router: Router) {
  router.beforeEach((to) => {
    const token = localStorage.getItem('dataocean_token')
    const user = JSON.parse(localStorage.getItem('dataocean_user') || 'null') as {
      passwordChanged?: boolean
      permissions?: string[]
    } | null

    if (to.path === '/login') {
      return token ? '/admin/users' : true
    }

    if (to.path !== '/login' && to.path !== '/change-password' && !token) {
      return '/login'
    }

    if (token && user && user.passwordChanged === false && to.path !== '/change-password') {
      return '/change-password?forced=1'
    }

    const requiredPermission = to.meta.permission as string | undefined
    if (requiredPermission && !user?.permissions?.includes('*') && !user?.permissions?.includes(requiredPermission)) {
      return '/admin'
    }

    return true
  })
}
