import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../../../../stores/auth'
import { useAdminContextStore } from '../../../../stores/adminContext'
import { useIamS1Store } from '../../../../stores/iamS1'
import { positiveId } from '../../../../utils/lineageContext'
import type { QualityRule } from '../../../../api/admin/governance'

export const RULE_VIEW = 'governance:rule:view'
export const RULE_MANAGE = 'governance:rule:manage'
export const METADATA_VIEW = 'metadata:view'

/** 配置按源授权，规划才依赖快照；辅助目录权限不能成为主规则编辑的门禁。 */
export function useQualityRuleScope() {
  const auth = useAuthStore(),
    context = useAdminContextStore(),
    iamS1 = useIamS1Store(),
    route = useRoute()
  const scopeMode = ref<'GLOBAL' | 'DATASOURCE'>('DATASOURCE')
  const sourceContextValid = computed(() =>
    Boolean(
      context.datasourceId &&
        !context.loading &&
        !context.routeSyncing &&
        (route.query.datasourceId === undefined ||
          positiveId(route.query.datasourceId) === context.datasourceId),
    ),
  )
  const permissionReady = computed(() => iamS1.loaded && !iamS1.errorMessage)
  // 请求归属包括身份、能力与显式源；快照只加入规划键，避免切快照丢失源级配置草稿。
  const readKey = computed(() =>
    JSON.stringify([
      auth.token,
      iamS1.snapshot,
      iamS1.loaded,
      iamS1.errorMessage,
      context.datasourceId,
      context.loading,
      context.routeSyncing,
      route.query.datasourceId,
      scopeMode.value,
    ]),
  )
  const planScopeValid = computed(() =>
    Boolean(
      context.snapshotId &&
        !context.routeSyncError &&
        (route.query.snapshotId === undefined || positiveId(route.query.snapshotId) === context.snapshotId),
    ),
  )
  const planKey = computed(() =>
    JSON.stringify([readKey.value, context.snapshotId, route.query.snapshotId, context.routeSyncError]),
  )
  const canViewRules = computed(
    () =>
      permissionReady.value &&
      (scopeMode.value === 'GLOBAL'
        ? iamS1.hasGlobal(RULE_VIEW)
        : Boolean(sourceContextValid.value && iamS1.canOnDatasource(RULE_VIEW, context.datasourceId))),
  )
  const canManageSelectedScope = computed(
    () =>
      permissionReady.value &&
      (scopeMode.value === 'GLOBAL'
        ? iamS1.systemAdmin
        : Boolean(sourceContextValid.value && iamS1.canOnDatasource(RULE_MANAGE, context.datasourceId))),
  )
  const canViewCheckItems = computed(
    () =>
      permissionReady.value &&
      (iamS1.hasGlobal(RULE_VIEW) || iamS1.datasourcesWithFunction(RULE_VIEW).length > 0),
  )
  const canConfigureRule = computed(() => canViewRules.value && canManageSelectedScope.value)

  // 同时要求原范围的查看和维护能力，变更目标范围由表单和服务端再次裁决。
  function canManageRule(rule: Pick<QualityRule, 'scopeType' | 'datasourceId'>) {
    if (!permissionReady.value) return false
    return rule.scopeType === 'GLOBAL'
      ? iamS1.systemAdmin && iamS1.hasGlobal(RULE_VIEW)
      : Boolean(
          rule.datasourceId &&
            iamS1.canOnDatasource(RULE_VIEW, rule.datasourceId) &&
            iamS1.canOnDatasource(RULE_MANAGE, rule.datasourceId),
        )
  }
  return {
    auth,
    context,
    iamS1,
    route,
    scopeMode,
    permissionReady,
    sourceContextValid,
    readKey,
    planKey,
    planScopeValid,
    canViewRules,
    canManageSelectedScope,
    canViewCheckItems,
    canConfigureRule,
    canManageRule,
  }
}
