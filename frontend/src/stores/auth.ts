import { defineStore } from 'pinia'
import { login, type LoginPayload, type LoginResult } from '../api/auth'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('dataocean_token') || '',
    user: JSON.parse(localStorage.getItem('dataocean_user') || 'null') as LoginResult | null,
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
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('dataocean_token')
      localStorage.removeItem('dataocean_user')
    },
  },
})
