import { defineStore } from 'pinia'
import { login, logout, me, type CurrentUser, type LoginPayload, type LoginResult } from '../api/auth'

function safeJsonParse<T>(key: string): T | null {
  try {
    const raw = localStorage.getItem(key)
    return raw ? JSON.parse(raw) : null
  } catch {
    localStorage.removeItem(key)
    return null
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('dataocean_token') || '',
    user: safeJsonParse<LoginResult>('dataocean_user'),
    currentUser: safeJsonParse<CurrentUser>('dataocean_current_user'),
  }),
  getters: {
    permissions: (state) => state.currentUser?.permissions || state.user?.permissions || [],
    hasPermission(): (permission: string) => boolean {
      return (permission: string) => this.permissions.includes('*') || this.permissions.includes(permission)
    },
    hasAnyPermission(): (permissions: string[]) => boolean {
      return (permissions: string[]) => this.permissions.includes('*') || permissions.some((permission) => this.permissions.includes(permission))
    },
  },
  actions: {
    async login(payload: LoginPayload) {
      const result = await login(payload)
      this.token = result.data.token
      this.user = result.data
      localStorage.setItem('dataocean_token', result.data.token)
      localStorage.setItem('dataocean_user', JSON.stringify(result.data))
      return result
    },
    async fetchUserInfo() {
      const result = await me()
      this.currentUser = result.data
      localStorage.setItem('dataocean_current_user', JSON.stringify(result.data))
      return result
    },
    async logout() {
      if (this.token) {
        await logout().catch(() => undefined)
      }
      this.token = ''
      this.user = null
      this.currentUser = null
      localStorage.removeItem('dataocean_token')
      localStorage.removeItem('dataocean_user')
      localStorage.removeItem('dataocean_current_user')
    },
  },
})
