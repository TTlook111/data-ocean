import { defineStore } from 'pinia'
import { login, logout, me, type CurrentUser, type LoginPayload, type LoginResult } from '../api/auth'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('dataocean_token') || '',
    user: JSON.parse(localStorage.getItem('dataocean_user') || 'null') as LoginResult | null,
    currentUser: JSON.parse(localStorage.getItem('dataocean_current_user') || 'null') as CurrentUser | null,
  }),
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
