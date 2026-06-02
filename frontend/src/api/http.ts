import axios from 'axios'
import router from '../router'

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 10000,
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('dataocean_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/** 防止多个并发 401 响应触发重复跳转 */
let isRedirecting = false

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401 && !isRedirecting) {
      isRedirecting = true
      localStorage.removeItem('dataocean_token')
      localStorage.removeItem('dataocean_user')
      if (router.currentRoute.value.path !== '/login') {
        router.push({ path: '/login', query: { expired: '1' } }).finally(() => {
          isRedirecting = false
        })
      } else {
        isRedirecting = false
      }
    }
    return Promise.reject(error)
  },
)
