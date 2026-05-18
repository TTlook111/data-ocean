import { http } from './http'

export interface LoginPayload {
  username: string
  password: string
}

export interface LoginResult {
  token: string
  tokenType: string
  expiresIn: number
  userId: number
  username: string
  realName: string
  roles: string[]
  permissions: string[]
}

interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export async function login(payload: LoginPayload) {
  const { data } = await http.post<ApiResult<LoginResult>>('/api/auth/login', payload)
  return data
}
