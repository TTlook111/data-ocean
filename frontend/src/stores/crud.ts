import { ref, type Ref } from 'vue'
import { extractError } from '../utils/error'

/**
 * 通用 CRUD Store 工厂函数
 *
 * 提供统一的列表查询、创建、更新、删除操作封装，
 * 减少各页面重复的加载状态、错误处理代码。
 *
 * @param api API 接口对象
 * @returns CRUD 操作方法和状态
 */
export function useCrudStore<T extends { id: number }>(api: {
  list: (params?: any) => Promise<any>
  create?: (data: any) => Promise<any>
  update?: (id: number, data: any) => Promise<any>
  delete?: (id: number) => Promise<any>
}) {
  const items = ref<T[]>([]) as Ref<T[]>
  const total = ref(0)
  const loading = ref(false)
  const error = ref<string | null>(null)

  /**
   * 查询列表
   */
  async function fetch(params?: any) {
    loading.value = true
    error.value = null
    try {
      const res = await api.list(params)
      items.value = res.data?.records ?? res.data ?? []
      total.value = res.data?.total ?? items.value.length
    } catch (e) {
      error.value = extractError(e, '列表加载失败')
    } finally {
      loading.value = false
    }
  }

  /**
   * 创建记录
   */
  async function create(data: any): Promise<boolean> {
    if (!api.create) return false
    loading.value = true
    error.value = null
    try {
      await api.create(data)
      return true
    } catch (e) {
      error.value = extractError(e, '创建失败')
      return false
    } finally {
      loading.value = false
    }
  }

  /**
   * 更新记录
   */
  async function update(id: number, data: any): Promise<boolean> {
    if (!api.update) return false
    loading.value = true
    error.value = null
    try {
      await api.update(id, data)
      return true
    } catch (e) {
      error.value = extractError(e, '更新失败')
      return false
    } finally {
      loading.value = false
    }
  }

  /**
   * 删除记录
   */
  async function remove(id: number): Promise<boolean> {
    if (!api.delete) return false
    loading.value = true
    error.value = null
    try {
      await api.delete(id)
      return true
    } catch (e) {
      error.value = extractError(e, '删除失败')
      return false
    } finally {
      loading.value = false
    }
  }

  return {
    items,
    total,
    loading,
    error,
    fetch,
    create,
    update,
    remove,
  }
}
