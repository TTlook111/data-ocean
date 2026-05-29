import { http } from '../http'

export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

export interface UserItem {
  id: number
  username: string
  realName: string
  email?: string
  phone?: string
  departmentId?: number
  departmentName?: string
  roleIds?: number[]
  roleNames?: string[]
  roleCodes?: string[]
  status: number
  lastLoginAt?: string
  createdAt?: string
}

export interface UserQuery {
  username?: string
  realName?: string
  departmentId?: number
  status?: number
  page?: number
  pageSize?: number
}

export interface UserPayload {
  username?: string
  password?: string
  realName: string
  email?: string
  phone?: string
  departmentId?: number
  roleIds: number[]
}

export interface RoleItem {
  id: number
  roleCode: string
  roleName: string
  description?: string
  status: number
  createdAt?: string
}

export interface DepartmentNode {
  id: number
  parentId?: number
  deptName: string
  deptCode: string
  sortOrder?: number
  children?: DepartmentNode[]
}

export interface DepartmentPayload {
  parentId?: number
  deptName: string
  deptCode: string
  sortOrder?: number
}

export async function listUsers(params: UserQuery) {
  const { data } = await http.get<ApiResult<PageResult<UserItem>>>('/api/admin/users', { params })
  return data
}

export async function createUser(payload: UserPayload) {
  const { data } = await http.post<ApiResult<{ id: number }>>('/api/admin/users', payload)
  return data
}

export async function updateUser(id: number, payload: UserPayload) {
  const { data } = await http.put<ApiResult<null>>(`/api/admin/users/${id}`, payload)
  return data
}

export async function deleteUser(id: number) {
  const { data } = await http.delete<ApiResult<null>>(`/api/admin/users/${id}`)
  return data
}

export async function updateUserStatus(id: number, status: number) {
  const { data } = await http.patch<ApiResult<null>>(`/api/admin/users/${id}/status`, { status })
  return data
}

export async function resetUserPassword(id: number) {
  const { data } = await http.post<ApiResult<{ tempPassword: string }>>(`/api/admin/users/${id}/reset-password`)
  return data
}

export async function listRoles() {
  const { data } = await http.get<ApiResult<RoleItem[]>>('/api/admin/roles')
  return data
}

export async function listRoleUsers(roleId: number) {
  const { data } = await http.get<ApiResult<UserItem[]>>(`/api/admin/roles/${roleId}/users`)
  return data
}

export async function assignRoleToUser(roleId: number, userId: number) {
  const { data } = await http.post<ApiResult<null>>(`/api/admin/roles/${roleId}/users`, { userId })
  return data
}

export async function removeRoleFromUser(roleId: number, userId: number) {
  const { data } = await http.delete<ApiResult<null>>(`/api/admin/roles/${roleId}/users/${userId}`)
  return data
}

export async function listDepartments() {
  const { data } = await http.get<ApiResult<DepartmentNode[]>>('/api/admin/departments/tree')
  return data
}

export async function createDepartment(payload: DepartmentPayload) {
  const { data } = await http.post<ApiResult<{ id: number }>>('/api/admin/departments', payload)
  return data
}

export async function deleteDepartment(id: number) {
  const { data } = await http.delete<ApiResult<null>>(`/api/admin/departments/${id}`)
  return data
}
