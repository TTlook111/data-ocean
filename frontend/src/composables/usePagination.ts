import { ref, computed, watch, type Ref } from 'vue'

/**
 * 分页逻辑 Composable
 *
 * 提供统一的分页状态管理和分页数据计算。
 *
 * @param data 源数据引用
 * @param defaultPageSize 默认每页条数
 * @returns 分页状态和方法
 */
export function usePagination<T>(data: Ref<T[]>, defaultPageSize: number = 20) {
  const currentPage = ref(1)
  const pageSize = ref(defaultPageSize)

  /**
   * 总条数
   */
  const total = computed(() => data.value.length)

  /**
   * 总页数
   */
  const totalPages = computed(() => Math.ceil(total.value / pageSize.value))

  /**
   * 当前页数据
   */
  const pagedData = computed(() => {
    const start = (currentPage.value - 1) * pageSize.value
    return data.value.slice(start, start + pageSize.value)
  })

  /**
   * 是否有上一页
   */
  const hasPrev = computed(() => currentPage.value > 1)

  /**
   * 是否有下一页
   */
  const hasNext = computed(() => currentPage.value < totalPages.value)

  /**
   * 跳转到指定页
   */
  function goToPage(page: number) {
    if (page >= 1 && page <= totalPages.value) {
      currentPage.value = page
    }
  }

  /**
   * 上一页
   */
  function prevPage() {
    if (hasPrev.value) {
      currentPage.value--
    }
  }

  /**
   * 下一页
   */
  function nextPage() {
    if (hasNext.value) {
      currentPage.value++
    }
  }

  /**
   * 修改每页条数
   */
  function setPageSize(size: number) {
    pageSize.value = size
    currentPage.value = 1 // 重置到第一页
  }

  // 数据变化时重置到第一页
  watch(data, () => {
    currentPage.value = 1
  })

  return {
    currentPage,
    pageSize,
    total,
    totalPages,
    pagedData,
    hasPrev,
    hasNext,
    goToPage,
    prevPage,
    nextPage,
    setPageSize,
  }
}
