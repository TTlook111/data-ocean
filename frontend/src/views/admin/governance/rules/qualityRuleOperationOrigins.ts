// 每个浏览器 SPA 独立持有自己发起的操作，跨组件导航保留；令牌只作为内存身份，不落盘。
const origins = new Map<string, Set<string>>()
export function qualityRuleOperationOrigins(identity: string) {
  let current = origins.get(identity)
  if (!current) {
    current = new Set<string>()
    origins.set(identity, current)
  }
  return current
}
export function clearQualityRuleOperationOrigins() {
  origins.clear()
}
