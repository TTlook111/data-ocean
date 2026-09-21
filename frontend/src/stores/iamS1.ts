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

  /**
   * 只服务独立问数入口、不构成后台能力的 S1 功能码。
   * 与后端 `IamS1CapabilitySnapshotVO` 的 `queryUse` / `viewSql` / `export` 三个布尔同源。
   * 判定后台入口时必须排除它们：否则一个只持有“普通问数用户”角色（功能码就是 `query:use`）
   * 的用户会被当成有后台能力，被放行进 /admin/**。
   */
  const QUERY_ONLY_FUNCTIONS: ReadonlySet<string> = new Set(['query:use', 'query:sql:view', 'query:export'])

  function isAdminFunction(functionCode: string): boolean {
    return !QUERY_ONLY_FUNCTIONS.has(functionCode)
  }

  /** 是否存在任意 S1 后台能力；用于粗粒度后台入口判定。问数功能不算后台能力。 */
  const hasAnyAdminCapability = computed(
    () =>
      systemAdmin.value ||
      globalFunctions.value.some(isAdminFunction) ||
      datasourceCapabilities.value.some((item) => item.functionCodes.some(isAdminFunction)),
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

  /**
   * 在某个功能上可操作的数据源。
   *
   * 服务端按“负责源”下发数据源列表（不按功能过滤），所以页面若直接把这份列表填进下拉，
   * 就会把“A 角色给的功能”和“B 角色给的负责源”交叉相乘——下拉里出现一个后端一定会拒绝的源。
   * 页面的数据源下拉必须改用本方法，只保留“功能与负责源在同一绑定上同时成立”的源。
   */
  function datasourcesWithFunction(functionCode: string): number[] {
    if (systemAdmin.value) return datasourceCapabilities.value.map((item) => item.datasourceId)
    return datasourceCapabilities.value
      .filter((item) => item.functionCodes.includes(functionCode))
      .map((item) => item.datasourceId)
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
    datasourcesWithFunction,
    load,
    reset,
  }
})
