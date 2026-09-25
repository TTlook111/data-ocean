import { defineStore } from 'pinia'
import { login, logout, me, type CurrentUser, type LoginPayload, type LoginResult } from '../api/auth'
import { useIamS1Store } from './iamS1'

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
  actions: {
    async login(payload: LoginPayload) {
      const result = await login(payload)
      // 换账号必须丢弃上一位用户的 S1 能力快照：登录/登出都是 SPA 内跳转，
      // 不会重新加载页面，store 里的 loaded 短路会让新用户沿用旧能力。
      useIamS1Store().reset()
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
      // 与 login 同理：不清空会让下一位登录者直接复用上一位用户的 S1 能力摘要。
      useIamS1Store().reset()
      this.token = ''
      this.user = null
      this.currentUser = null
      localStorage.removeItem('dataocean_token')
      localStorage.removeItem('dataocean_user')
      localStorage.removeItem('dataocean_current_user')
    },
  },
})
