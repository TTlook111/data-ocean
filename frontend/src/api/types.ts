/**
 * 通用 API 响应类型定义。
 * 所有 API 模块统一从此文件引入。
 */

/** 后端统一响应结构 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

/** 分页响应结构 */
export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  size: number
  pages?: number
}
