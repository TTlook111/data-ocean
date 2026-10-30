import { defineStore } from 'pinia'
import { login, logout, me, type CurrentUser, type LoginPayload, type LoginResult } from '../api/auth'
import { useIamS1Store } from './iamS1'
import { useAdminContextStore } from './adminContext'

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
    loginAttempt: 0,
    token: localStorage.getItem('dataocean_token') || '',
    user: safeJsonParse<LoginResult>('dataocean_user'),
    currentUser: safeJsonParse<CurrentUser>('dataocean_current_user'),
  }),
  actions: {
    async login(payload: LoginPayload, ownsRequest: () => boolean = () => true) {
      const attempt = ++this.loginAttempt, previousToken = this.token
      const result = await login(payload)
      if (attempt !== this.loginAttempt || this.token !== previousToken || !ownsRequest()) throw new Error('登录请求已过期，请使用当前登录页面重试')
      if (!result.data?.token || !result.data.username) throw new Error('登录回执不完整，请重新登录')
      // 换账号必须丢弃上一位用户的 S1 能力快照：登录/登出都是 SPA 内跳转，
      // 不会重新加载页面，store 里的 loaded 短路会让新用户沿用旧能力。
      useIamS1Store().reset()
      useAdminContextStore().resetSession()
      this.token = result.data.token
      this.user = result.data
      this.currentUser = null
      localStorage.setItem('dataocean_token', result.data.token)
      localStorage.setItem('dataocean_user', JSON.stringify(result.data))
      localStorage.removeItem('dataocean_current_user')
      return result
    },
    async fetchUserInfo() {
      const requestToken = this.token
      const result = await me()
      if (!requestToken || this.token !== requestToken) return result
      this.currentUser = result.data
      localStorage.setItem('dataocean_current_user', JSON.stringify(result.data))
      return result
    },
    invalidateSession(expectedToken: string) {
      if (this.token !== expectedToken) return
      this.loginAttempt++
      useIamS1Store().reset()
      useAdminContextStore().resetSession()
      this.token = ''
      this.user = null
      this.currentUser = null
      localStorage.removeItem('dataocean_token')
      localStorage.removeItem('dataocean_user')
      localStorage.removeItem('dataocean_current_user')
    },
    async logout() {
      this.loginAttempt++
      const requestToken = this.token
      if (requestToken) {
        await logout().catch(() => undefined)
      }
      if (this.token !== requestToken) return
      // 与 login 同理：不清空会让下一位登录者直接复用上一位用户的 S1 能力摘要。
      useIamS1Store().reset()
      useAdminContextStore().resetSession()
      this.token = ''
      this.user = null
      this.currentUser = null
      localStorage.removeItem('dataocean_token')
      localStorage.removeItem('dataocean_user')
      localStorage.removeItem('dataocean_current_user')
    },
  },
})
