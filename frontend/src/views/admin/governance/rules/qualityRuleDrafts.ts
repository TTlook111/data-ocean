import { computed, reactive, ref } from 'vue'
import type { QualityRule, QualityRulePayload } from '../../../../api/admin/governance'

export type RuleScope = QualityRule['scopeType']
interface RuleDraft {
  form: QualityRulePayload
  baseline: QualityRule | null
  initialSignature: string
}
interface DraftOperation {
  action: string
  ruleId?: number
  draftKey?: string
  original: { scopeType: RuleScope; datasourceId?: number }
}

// 人工草稿仅存当前进程内存，不写入浏览器持久存储；身份结束时由认证 store 主动清理。
const drafts = new Map<string, Map<string, RuleDraft>>()
export function clearQualityRuleDrafts() {
  drafts.clear()
}
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))

/** 编辑草稿与服务端版本分离，关闭、换源和返回仍使用原版本，冲突不得偷偷换基线。 */
export function useQualityRuleDrafts(
  identity: () => string,
  canManage: (value: Pick<QualityRule, 'scopeType' | 'datasourceId'>) => boolean,
) {
  const editingRuleId = ref<number>()
  const editingRule = ref<QualityRule | null>(null)
  const form = reactive({
    ruleName: '',
    templateCode: '',
    description: '',
    scopeType: 'DATASOURCE' as RuleScope,
    datasourceId: undefined as number | undefined,
    targetTableName: '',
    targetColumnName: '',
    parameters: {} as Record<string, number | boolean>,
    severity: 'MEDIUM',
    deductionPoints: 3,
    enabled: true,
  })
  const draftRestored = ref(false)
  const initialSignature = ref('')
  let activeKey = '',
    owner = ''
  const draftDirty = computed(() => Boolean(activeKey && JSON.stringify(form) !== initialSignature.value))

  // 请求只携带配置字段；恢复元信息和 UI 状态不能混入保存正文。
  function payload(): QualityRulePayload {
    return {
      ruleName: form.ruleName.trim(),
      templateCode: form.templateCode,
      description: form.description.trim() || undefined,
      scopeType: form.scopeType,
      datasourceId: form.datasourceId,
      targetTableName: form.targetTableName || undefined,
      targetColumnName: form.targetColumnName || undefined,
      parameters: { ...form.parameters },
      severity: form.severity,
      deductionPoints: Number(form.deductionPoints),
      enabled: form.enabled,
    }
  }
  function rememberDraft(retainUnverified = false) {
    if (
      !activeKey ||
      owner !== identity() ||
      (!retainUnverified && (!canManage(form) || (editingRule.value && !canManage(editingRule.value))))
    )
      return
    const owned = drafts.get(owner) ?? new Map<string, RuleDraft>()
    if (draftDirty.value)
      owned.set(activeKey, {
        form: clone(form),
        baseline: clone(editingRule.value),
        initialSignature: initialSignature.value,
      })
    else owned.delete(activeKey)
    drafts.set(owner, owned)
  }
  function deactivateDraft() {
    activeKey = ''
    draftRestored.value = false
  }

  // 调用方先准备默认值，再恢复同对象草稿；传入较新列表行也不覆盖原人工编辑基线。
  function activateDraft(ruleId: number | undefined, scope: RuleScope, source: number | undefined) {
    owner = identity()
    activeKey = ruleId ? `rule:${ruleId}` : `create:${scope === 'GLOBAL' ? 'GLOBAL' : source}`
    initialSignature.value = JSON.stringify(form)
    const draft = drafts.get(owner)?.get(activeKey)
    if (!draft) return
    if (!canManage(draft.form) || (draft.baseline && !canManage(draft.baseline))) {
      drafts.get(owner)?.delete(activeKey)
      return
    }
    Object.assign(form, {
      ...clone(draft.form),
      datasourceId: draft.form.datasourceId,
      description: draft.form.description || '',
      targetTableName: draft.form.targetTableName || '',
      targetColumnName: draft.form.targetColumnName || '',
      parameters: clone(draft.form.parameters || {}),
    })
    editingRule.value = clone(draft.baseline)
    editingRuleId.value = draft.baseline?.id
    initialSignature.value = draft.initialSignature
    draftRestored.value = true
  }
  function discardDraft() {
    if (activeKey) drafts.get(owner)?.delete(activeKey)
    initialSignature.value = JSON.stringify(form)
    draftRestored.value = false
  }
  function pruneDrafts() {
    for (const [token, records] of drafts) {
      if (token !== identity()) {
        drafts.delete(token)
        continue
      }
      for (const [key, draft] of records) {
        if (!canManage(draft.form) || (draft.baseline && !canManage(draft.baseline))) records.delete(key)
      }
    }
  }
  // 回执只能清理它所对应的草稿；旧来源的迟到拒绝不得触碰后来打开的另一个来源。
  function operationKey(operation: DraftOperation) {
    return (
      operation.draftKey ||
      (operation.action === 'CREATE'
        ? `create:${operation.original.scopeType === 'GLOBAL' ? 'GLOBAL' : operation.original.datasourceId}`
        : `rule:${operation.ruleId}`)
    )
  }
  function hasOperationDraft(operation: DraftOperation) {
    const key = operationKey(operation)
    return drafts.get(identity())?.has(key) || (owner === identity() && activeKey === key && draftDirty.value)
  }
  function discardOperationDraft(operation: DraftOperation) {
    const key = operationKey(operation)
    drafts.get(identity())?.delete(key)
    const current = owner === identity() && activeKey === key
    if (current) {
      initialSignature.value = JSON.stringify(form)
      draftRestored.value = false
    }
    return current
  }
  return {
    form,
    editingRuleId,
    editingRule,
    draftRestored,
    draftDirty,
    payload,
    rememberDraft,
    deactivateDraft,
    activateDraft,
    discardDraft,
    pruneDrafts,
    hasOperationDraft,
    discardOperationDraft,
    draftKey: () => activeKey,
  }
}
