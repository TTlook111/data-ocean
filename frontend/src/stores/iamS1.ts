/**
 * IAM-SIMPLE-1 能力摘要 Store
 *
 * 只保存 Java 返回的 S1 能力结论，用于界面可见性（路由、工作区、Tab、按钮）。
 * 明确不使用旧角色的 permissions / roles 数组作为 S1 授权来源：
 * 旧权限只服务切换前的旧运行环境，不能给新体系授权，后端也不会据此放行。
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getIamS1Capabilities, type IamS1CapabilitySnapshot } from '../api/iamS1'

export const useIamS1Store = defineStore('iamS1', () => {
  const snapshot = ref<IamS1CapabilitySnapshot | null>(null)
  const loading = ref(false)
  const loaded = ref(false)
  const errorMessage = ref<string | null>(null)
  let inflight: Promise<void> | null = null

  const systemAdmin = computed(() => snapshot.value?.systemAdmin === true)
  const globalFunctions = computed(() => snapshot.value?.globalFunctions ?? [])
  const datasourceCapabilities = computed(() => snapshot.value?.datasourceCapabilities ?? [])
  const queryUse = computed(() => snapshot.value?.queryUse === true)
  const viewSql = computed(() => snapshot.value?.viewSql === true)
  const exportResult = computed(() => snapshot.value?.export === true)

  /** 是否存在任意 S1 后台能力；用于粗粒度后台入口判定。 */
  const hasAnyAdminCapability = computed(
    () => globalFunctions.value.length > 0 || datasourceCapabilities.value.length > 0,
  )

  /** 全局功能判定（不依赖数据源负责范围）。 */
  function hasGlobal(functionCode: string): boolean {
    if (systemAdmin.value) return true
    return globalFunctions.value.includes(functionCode)
  }

  /**
   * 数据源范围功能判定。
   * 只有“功能与负责源在同一个用户角色绑定上”同时成立才为真，与后端同一绑定规则一致。
   */
  function canOnDatasource(functionCode: string, datasourceId?: number): boolean {
    if (systemAdmin.value) return true
    if (datasourceId === undefined) return false
    const capability = datasourceCapabilities.value.find((item) => item.datasourceId === datasourceId)
    return capability ? capability.functionCodes.includes(functionCode) : false
  }

  /** 某数据源上可用的后台功能中文名，用于页面说明。 */
  function functionNamesOn(datasourceId?: number): string[] {
    if (systemAdmin.value) return ['系统管理员：全部后台功能']
    if (datasourceId === undefined) return []
    return datasourceCapabilities.value.find((item) => item.datasourceId === datasourceId)?.functionNames ?? []
  }

  async function load(force = false): Promise<void> {
    if (loading.value) return inflight ?? Promise.resolve()
    if (loaded.value && !force) return
    loading.value = true
    inflight = (async () => {
      try {
        const result = await getIamS1Capabilities()
        snapshot.value = result.data
        errorMessage.value = null
        loaded.value = true
      } catch (error) {
        snapshot.value = null
        errorMessage.value =
          error instanceof Error ? error.message : '无法读取 IAM-SIMPLE-1 能力摘要，请稍后重试'
      } finally {
        loading.value = false
        inflight = null
      }
    })()
    return inflight
  }

  /** 退出登录或切换账号时清空，避免沿用上一位用户的界面能力。 */
  function reset() {
    snapshot.value = null
    loaded.value = false
    errorMessage.value = null
  }

  return {
    snapshot,
    loading,
    loaded,
    errorMessage,
    systemAdmin,
    globalFunctions,
    datasourceCapabilities,
    queryUse,
    viewSql,
    exportResult,
    hasAnyAdminCapability,
    hasGlobal,
    canOnDatasource,
    functionNamesOn,
    load,
    reset,
  }
})
