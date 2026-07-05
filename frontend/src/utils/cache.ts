/**
 * 前端缓存工具
 *
 * 提供带 TTL 的内存缓存，减少重复 API 请求。
 */

interface CacheEntry<T> {
  data: T
  timestamp: number
}

const cache = new Map<string, CacheEntry<any>>()

/** 默认缓存时长：5 分钟 */
const DEFAULT_CACHE_DURATION = 5 * 60 * 1000

/** 清理定时器 */
let cleanupTimer: ReturnType<typeof setInterval> | null = null

/**
 * 带缓存的 API 请求
 *
 * @param key 缓存键
 * @param apiFn API 请求函数
 * @param duration 缓存时长（毫秒）
 * @returns 请求结果
 */
export async function fetchWithCache<T>(
  key: string,
  apiFn: () => Promise<T>,
  duration: number = DEFAULT_CACHE_DURATION
): Promise<T> {
  const cached = cache.get(key)
  if (cached && Date.now() - cached.timestamp < duration) {
    return cached.data
  }

  const data = await apiFn()
  cache.set(key, { data, timestamp: Date.now() })
  return data
}

/**
 * 清除指定缓存
 *
 * @param key 缓存键
 */
export function clearCache(key: string): void {
  cache.delete(key)
}

/**
 * 清除所有缓存
 */
export function clearAllCache(): void {
  cache.clear()
}

/**
 * 清除过期缓存
 */
export function clearExpiredCache(): void {
  const now = Date.now()
  for (const [key, entry] of cache.entries()) {
    if (now - entry.timestamp > DEFAULT_CACHE_DURATION) {
      cache.delete(key)
    }
  }
}

/**
 * 启动缓存自动清理
 *
 * @param intervalMs 清理间隔（毫秒），默认 1 分钟
 */
export function startCacheCleanup(intervalMs: number = 60_000): void {
  if (cleanupTimer) return
  cleanupTimer = setInterval(clearExpiredCache, intervalMs)
}

/**
 * 停止缓存自动清理
 */
export function stopCacheCleanup(): void {
  if (cleanupTimer) {
    clearInterval(cleanupTimer)
    cleanupTimer = null
  }
}
