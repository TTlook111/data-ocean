import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  getQualityRuleOperation,
  resolveQualityRuleOperation,
  submitQualityRuleOperation,
  type QualityRuleAction,
  type QualityRuleOperationReceipt,
  type QualityRuleOperationRequest,
} from '../../../../api/admin/qualityRuleOperations'
import { getHttpErrorMessage } from '../../../../utils/httpError'
import type { useQualityRuleScope } from './useQualityRuleScope'
import { qualityRuleOperationOrigins } from './qualityRuleOperationOrigins'

interface OperationScope {
  scopeType: 'GLOBAL' | 'DATASOURCE' | 'TABLE' | 'COLUMN'
  datasourceId?: number
}
export interface PendingRuleOperation {
  operationId: string
  action: QualityRuleAction
  ruleId?: number
  original: OperationScope
  target: OperationScope
  createdAt: number
  draftKey?: string
  applied?: { ruleId: number; configVersion: number }
  consumed?: boolean
}
const STORAGE_PREFIX = 'dataocean_quality_rule_operations_v1:'
const ACTION_LABELS: Record<QualityRuleAction, string> = {
  CREATE: '创建规则',
  UPDATE: '保存规则',
  COPY: '复制规则',
  ENABLE: '启用规则',
  DISABLE: '停用规则',
  DELETE: '删除规则',
  RESTORE: '恢复规则',
}

/** 仅持久化回执定位所需标识；草稿、冻结正文、令牌和服务端内容不落浏览器存储。 */
export function useQualityRuleOperations(
  scope: ReturnType<typeof useQualityRuleScope>,
  onRevoked: (record: PendingRuleOperation) => void,
) {
  const { auth, iamS1, permissionReady, canManageRule } = scope
  const records = ref<PendingRuleOperation[]>([])
  const busy = ref<string[]>([])
  const error = ref('')
  const notice = ref('')
  const storageReady = ref(false)
  const userId = computed(() => auth.currentUser?.id ?? auth.user?.userId)
  let disposed = false
  const storageKey = () => (userId.value ? `${STORAGE_PREFIX}${userId.value}` : '')
  const mayRead = (record: PendingRuleOperation) =>
    canManageRule(record.original) && canManageRule(record.target)
  const originatedHere = () => qualityRuleOperationOrigins(auth.token)
  const needsLocalConsumption = (record: PendingRuleOperation) =>
    !record.consumed || originatedHere().has(record.operationId)

  // 每个操作独占一个键，两个标签页追加/消费各自回执时不会覆盖对方的未知操作。
  function persist(record: PendingRuleOperation) {
    const key = storageKey()
    if (!key) throw new Error('当前身份尚未确认，无法保存操作回执。')
    localStorage.setItem(`${key}:${record.operationId}`, JSON.stringify(record))
    records.value = records.value.filter((item) => item.operationId !== record.operationId)
    if (permissionReady.value && mayRead(record) && needsLocalConsumption(record)) records.value.push(record)
  }
  function validRecord(value: unknown): PendingRuleOperation {
    const record = value as PendingRuleOperation | null
    const validScope = (value: OperationScope | undefined) =>
      value &&
      ['GLOBAL', 'DATASOURCE', 'TABLE', 'COLUMN'].includes(value.scopeType) &&
      (value.scopeType === 'GLOBAL' ||
        (Number.isSafeInteger(value.datasourceId) && Number(value.datasourceId) > 0))
    if (
      !record ||
      typeof record.operationId !== 'string' ||
      !/^[0-9a-f-]{36}$/i.test(record.operationId) ||
      !Object.hasOwn(ACTION_LABELS, record.action) ||
      !validScope(record.original) ||
      !validScope(record.target) ||
      (record.ruleId !== undefined && (!Number.isSafeInteger(record.ruleId) || record.ruleId <= 0)) ||
      (record.draftKey !== undefined &&
        !/^(rule:[1-9]\d*|create:(GLOBAL|[1-9]\d*))$/.test(record.draftKey)) ||
      (record.applied &&
        (!Number.isSafeInteger(record.applied.ruleId) ||
          record.applied.ruleId <= 0 ||
          !Number.isSafeInteger(record.applied.configVersion) ||
          record.applied.configVersion <= 0))
    ) {
      throw new Error('操作恢复标识格式错误')
    }
    // 明确白名单，旧记录或外部写入的多余字段不能被再次持久化为配置正文。
    return {
      operationId: record.operationId,
      action: record.action,
      ruleId: record.ruleId,
      original: { scopeType: record.original.scopeType, datasourceId: record.original.datasourceId },
      target: { scopeType: record.target.scopeType, datasourceId: record.target.datasourceId },
      createdAt: record.createdAt,
      draftKey: record.draftKey,
      applied: record.applied
        ? { ruleId: record.applied.ruleId, configVersion: record.applied.configVersion }
        : undefined,
      consumed: record.consumed === true,
    }
  }
  function restore() {
    storageReady.value = false
    if (!permissionReady.value || !storageKey()) {
      records.value = []
      return
    }
    try {
      const prefix = `${storageKey()}:`
      const keys = Array.from({ length: localStorage.length }, (_, index) => localStorage.key(index)).filter(
        (key): key is string => Boolean(key?.startsWith(prefix)),
      )
      const loaded = keys.map((key) => {
        const record = validRecord(JSON.parse(localStorage.getItem(key) || 'null'))
        if (key !== `${prefix}${record.operationId}`) throw new Error('操作标识不匹配')
        return record
      })
      // 本轮早期版本的数组只迁移必要标识；全部写成功后才移除旧键，避免丢失在途关联。
      const legacy = localStorage.getItem(storageKey())
      if (legacy) {
        const value: unknown = JSON.parse(legacy)
        if (!Array.isArray(value)) throw new Error('操作记录格式错误')
        for (const item of value) {
          const record = validRecord(item)
          if (!loaded.some((existing) => existing.operationId === record.operationId)) loaded.push(record)
        }
        for (const record of loaded) if (mayRead(record)) persist(record)
        localStorage.removeItem(storageKey())
      }
      records.value = loaded.filter((record) => {
        if (mayRead(record)) return needsLocalConsumption(record)
        localStorage.removeItem(`${prefix}${record.operationId}`)
        return false
      })
      storageReady.value = true
    } catch {
      records.value = []
      error.value = '本机操作记录不可读取；恢复存储后再进行规则写入。'
    }
  }
  function forget(operationId: string) {
    localStorage.removeItem(`${storageKey()}:${operationId}`)
    originatedHere().delete(operationId)
    records.value = records.value.filter((record) => record.operationId !== operationId)
  }
  function acknowledge(operationId: string) {
    const record = records.value.find((item) => item.operationId === operationId)
    if (!record?.applied) return
    if (originatedHere().has(operationId)) {
      // 原发起 SPA 已消费对应草稿，可清理自身标识；正常保存不会永久累积终态。
      forget(operationId)
    } else {
      // 其他标签页无法证明原工作面已消费，保留最小终态防止其仍存活草稿再次 CREATE。
      persist({ ...record, consumed: true })
    }
  }
  function conflicts(ruleId: number | undefined, _target: OperationScope) {
    return records.value.some((record) =>
      ruleId ? record.ruleId === ruleId || record.applied?.ruleId === ruleId : record.action === 'CREATE',
    )
  }
  function label(record: PendingRuleOperation) {
    return `${ACTION_LABELS[record.action]}${record.ruleId ? ` #${record.ruleId}` : ''}`
  }
  function own(token: string, ownerId: number | undefined) {
    return !disposed && token === auth.token && ownerId === userId.value
  }
  function validateReceipt(receipt: QualityRuleOperationReceipt | undefined, record: PendingRuleOperation) {
    if (
      !receipt ||
      receipt.operationId !== record.operationId ||
      !['APPLIED', 'REJECTED', 'CONFLICT', 'NOT_FOUND'].includes(receipt.outcome) ||
      (receipt.action && receipt.action !== record.action) ||
      (receipt.outcome === 'APPLIED' && (!receipt.ruleId || !receipt.configVersion))
    ) {
      throw new Error('操作回执不完整，结果仍未知，请核对原操作。')
    }
    return receipt
  }
  function complete(receipt: QualityRuleOperationReceipt, record: PendingRuleOperation) {
    if (permissionReady.value && !mayRead(record)) {
      // 已确认撤权之后的晚成功也不得恢复无权标识或版本；权限未知时只保留必要恢复记录。
      forget(record.operationId)
      onRevoked(record)
      return receipt
    }
    if (receipt.outcome === 'APPLIED') {
      // 提交成功与编辑工作面消费成功分开；关闭/刷新后未核对的 CREATE 不得再次创建。
      persist({ ...record, applied: { ruleId: receipt.ruleId!, configVersion: receipt.configVersion! } })
    } else if (receipt.outcome !== 'NOT_FOUND') {
      if (receipt.errorCode === 403 || receipt.errorCode === 404) revoke(record)
      else forget(record.operationId)
    }
    return receipt
  }
  function revoke(record: PendingRuleOperation) {
    forget(record.operationId)
    onRevoked(record)
    void iamS1.load(true)
  }

  // 先落最小恢复标识再发请求；超时/5xx/不完整回执均保留原 ID，绝不生成新 ID 自动补发。
  async function submit(
    request: Omit<QualityRuleOperationRequest, 'operationId'>,
    original: OperationScope,
    target = original,
    draftKey?: string,
  ) {
    restore()
    if (!storageReady.value) throw new Error('操作恢复记录尚未就绪，不能安全发起新的规则写入。')
    if (conflicts(request.ruleId, target)) throw new Error('此对象仍有结果未知的操作，请先核对原操作。')
    if (!canManageRule(original) || !canManageRule(target)) throw new Error('当前范围没有规则维护权限。')
    const record: PendingRuleOperation = {
      operationId: crypto.randomUUID(),
      action: request.action,
      ruleId: request.ruleId,
      original: { scopeType: original.scopeType, datasourceId: original.datasourceId },
      target: { scopeType: target.scopeType, datasourceId: target.datasourceId },
      createdAt: Date.now(),
      draftKey,
    }
    const token = auth.token,
      ownerId = userId.value
    const body = JSON.parse(
      JSON.stringify({ ...request, operationId: record.operationId }),
    ) as QualityRuleOperationRequest
    persist(record)
    originatedHere().add(record.operationId)
    busy.value = [...busy.value, record.operationId]
    try {
      const result = await submitQualityRuleOperation(body)
      const receipt = validateReceipt(result.data, record)
      if (own(token, ownerId)) complete(receipt, record)
      return receipt
    } catch (cause) {
      if (own(token, ownerId)) {
        const status = (cause as { response?: { status?: number } })?.response?.status
        if (status === 403 || status === 404) revoke(record)
        // 可确认的请求拒绝没有业务提交；冲突与校验失败保留人工草稿并允许明确的新操作。
        else if (status && status < 500 && ![408, 429].includes(status)) forget(record.operationId)
      }
      throw cause
    } finally {
      if (own(token, ownerId)) busy.value = busy.value.filter((id) => id !== record.operationId)
    }
  }

  async function reconcile(record: PendingRuleOperation, finish = false) {
    if (busy.value.includes(record.operationId) || !mayRead(record)) return
    const token = auth.token,
      ownerId = userId.value
    busy.value = [...busy.value, record.operationId]
    error.value = ''
    notice.value = ''
    try {
      const response = await (finish
        ? resolveQualityRuleOperation(record.operationId)
        : getQualityRuleOperation(record.operationId))
      if (!own(token, ownerId)) return
      const receipt = complete(validateReceipt(response.data, record), record)
      if (!permissionReady.value || !mayRead(record)) return receipt
      notice.value =
        receipt.outcome === 'APPLIED'
          ? `${label(record)}已提交，配置版本 v${receipt.configVersion}。`
          : receipt.outcome === 'NOT_FOUND'
            ? '尚无已提交回执。可继续核对，或核对并结束原操作后继续编辑。'
            : receipt.errorMessage ||
              (receipt.outcome === 'CONFLICT' ? '原操作因版本冲突未提交。' : '原操作已拒绝，未提交规则变更。')
      return receipt
    } catch (cause) {
      if (own(token, ownerId)) {
        const status = (cause as { response?: { status?: number } })?.response?.status
        if (status === 403 || status === 404) revoke(record)
        error.value = getHttpErrorMessage(cause, '回执核对失败，原操作仍保留。')
      }
    } finally {
      if (own(token, ownerId)) busy.value = busy.value.filter((id) => id !== record.operationId)
    }
  }
  // 能力未就绪只隐藏；已确认撤权才清除无权标识及关联草稿，不从临时网络失败推断撤权。
  watch(
    () => [auth.token, userId.value, permissionReady.value, iamS1.snapshot],
    () => {
      busy.value = []
      notice.value = ''
      error.value = ''
      restore()
    },
    { immediate: true, flush: 'sync' },
  )
  // 其他标签页只刷新最小回执目录，不读取其正文或重发其请求。
  const onStorage = (event: StorageEvent) => {
    if (!event.key || event.key === storageKey() || event.key.startsWith(`${storageKey()}:`)) restore()
  }
  window.addEventListener('storage', onStorage)
  onBeforeUnmount(() => {
    disposed = true
    window.removeEventListener('storage', onStorage)
  })
  return { records, busy, error, notice, submit, reconcile, conflicts, label, acknowledge, revoke }
}
