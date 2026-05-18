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
  passwordChanged: boolean
  roles: string[]
  permissions: string[]
}

export interface CurrentUser {
  id: number
  username: string
  realName: string
  passwordChanged: boolean
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

export async function logout() {
  const { data } = await http.post<ApiResult<null>>('/api/auth/logout')
  return data
}

export async function me() {
  const { data } = await http.get<ApiResult<CurrentUser>>('/api/auth/me')
  return data
}

export interface ChangePasswordPayload {
  oldPassword: string
  newPassword: string
}

export async function changePassword(payload: ChangePasswordPayload) {
  const { data } = await http.put<ApiResult<null>>('/api/auth/password', payload)
  return data
}
