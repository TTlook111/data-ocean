import { severityLabel } from './enumLabels'

export interface QualityRuleParameterDescription {
  key: string
  label: string
  type: 'INTEGER' | 'NUMBER' | 'BOOLEAN' | string
}

/** 列表、详情和表单共用的字段标签。 */
export const RULE_LABELS = {
  ruleName: '规则名称',
  templateCode: '检查项',
  scopeType: '适用范围',
  datasource: '数据源',
  targetTable: '目标表',
  targetColumn: '目标字段',
  severity: '严重程度',
  deductionPoints: '扣分',
  enabled: '启用状态',
  description: '处理建议',
  checkType: '检查方式',
  dimension: '治理维度',
  targetType: '检查目标',
} as const

/** 作用范围的规范顺序，不依赖后端 Set 的迭代顺序。 */
export const RULE_SCOPE_ORDER = ['GLOBAL', 'DATASOURCE', 'TABLE', 'COLUMN'] as const

const SCOPE_LABELS: Record<string, string> = {
  GLOBAL: '全局',
  DATASOURCE: '数据源',
  TABLE: '表',
  COLUMN: '字段',
}

const TARGET_TYPE_LABELS: Record<string, string> = {
  TABLE: '表',
  COLUMN: '字段',
  RELATION: '关系',
}

export function ruleScopeLabel(scope?: string): string {
  return SCOPE_LABELS[scope || ''] || '未知范围'
}

export function ruleTargetTypeLabel(targetType?: string): string {
  return TARGET_TYPE_LABELS[targetType || ''] || '未指定'
}

export function sortRuleScopes(scopes: readonly string[]): string[] {
  return [...scopes].sort((left, right) => {
    const leftIndex = RULE_SCOPE_ORDER.indexOf(left as typeof RULE_SCOPE_ORDER[number])
    const rightIndex = RULE_SCOPE_ORDER.indexOf(right as typeof RULE_SCOPE_ORDER[number])
    return (leftIndex < 0 ? Number.MAX_SAFE_INTEGER : leftIndex)
      - (rightIndex < 0 ? Number.MAX_SAFE_INTEGER : rightIndex)
  })
}

function numericParameter(parameters: Record<string, unknown>, key: string, fallback?: number) {
  const value = Number(parameters[key])
  return Number.isFinite(value) ? value : fallback
}

export function describeRuleCondition(
  templateCode: string | undefined,
  parameters: Record<string, unknown> = {},
  definitions: QualityRuleParameterDescription[] = [],
  templateDescription?: string,
) {
  switch (templateCode) {
    case 'TIME_TABLE_NO_UPDATE':
      return '采集估算行数为 0 的普通表会收到提示；这不能证明长期没有数据更新。'
    case 'DATA_STALE_TABLE': {
      const days = numericParameter(parameters, 'staleDays', 30)
      return `使用已注册的新鲜度检查器，超过 ${days} 天未更新时提示；具体时间依据见执行记录。`
    }
    case 'DATA_NULL_RATE_HIGH': {
      const threshold = numericParameter(parameters, 'threshold', 0.3) ?? 0.3
      return `只读抽样空值率超过 ${(threshold * 100).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}% 时提示。`
    }
    case 'DATA_UNIQUE_VIOLATION':
      return parameters.primaryKeyOnly === false
        ? '只读抽样检查字段重复值，适用于已声明为唯一的字段。'
        : '只读抽样检查主键字段重复值。'
    case 'TIME_SNAPSHOT_EXPIRED': {
      const days = numericParameter(parameters, 'maxAgeDays', 7)
      return `元数据快照采集时间早于 ${days} 天时提示。`
    }
    case 'ACCU_ENUM_ANOMALY': {
      const count = numericParameter(parameters, 'maxDistinctValues', 50)
      return `疑似枚举字段的去重值数量超过 ${count} 时提示。`
    }
    default: {
      const configured = definitions
        .filter((item) => Object.prototype.hasOwnProperty.call(parameters, item.key))
        .map((item) => `${item.label}：${typeof parameters[item.key] === 'boolean' ? (parameters[item.key] ? '是' : '否') : parameters[item.key]}`)
      return configured.length ? configured.join('；') : templateDescription || '按已注册检查模板执行。'
    }
  }
}

export function describeRuleScope(
  scopeType: string | undefined,
  datasourceName?: string,
  targetTableName?: string,
  targetColumnName?: string,
  datasourceMode = false,
) {
  if (scopeType === 'GLOBAL') return datasourceMode ? '全局默认 · 适用于当前数据源' : '全局 · 适用于所有数据源'
  const label = ruleScopeLabel(scopeType)
  const path = [datasourceName, targetTableName, targetColumnName].filter(Boolean).join(' · ')
  return path ? `${label} · ${path}` : label
}

export function describeRuleImpact(severity: string, deductionPoints: number) {
  return `${severityLabel(severity)} · 扣 ${deductionPoints} 分`
}

/** 检查能力的边界在目录、表单和执行记录中保持一致。 */
export function ruleEvidenceLimit(templateCode?: string) {
  switch (templateCode) {
    case 'TIME_TABLE_NO_UPDATE':
      return '仅依据采集时的估算行数，不能证明表为空或长期未更新。'
    case 'DATA_NULL_RATE_HIGH':
    case 'DATA_UNIQUE_VIOLATION':
      return '最多检查 100,000 行只读样本；样本内未发现问题不代表全表无问题。'
    case 'DATA_FK_ORPHAN':
      return '最多抽取 100,000 条引用端记录，再只读查询目标端；样本外问题可能未被发现。'
    case 'DATA_STALE_TABLE':
      return '需要可读的时间字段；无可用字段时记为不适用，不能视为检查通过。'
    default:
      return '依据所选快照的元数据检查；不代表业务数据已经全量验证。'
  }
}

export function ruleExecutionLabel(status?: string, issueCount = 0) {
  if (status === 'SUCCESS') return issueCount > 0 ? '执行完成 · 发现问题' : '执行完成 · 未发现问题'
  return ({ FAILURE: '执行失败', NOT_APPLICABLE: '不适用', NOT_EXECUTED: '未执行' } as Record<string, string>)[status || ''] || '执行状态未知'
}

export function qualityBatchLabel(status?: string) {
  return ({ RUNNING: '检查中', SUCCESS: '批次完成', PARTIAL: '批次部分失败', FAILED: '批次失败', LEGACY: '历史有限结果' } as Record<string, string>)[status || ''] || '批次状态未知'
}

export function rulePlanStatusLabel(status?: string) {
  return ({ READY: '可参与检查', FAILURE: '配置异常', NOT_APPLICABLE: '不适用或被覆盖', NOT_EXECUTED: '未纳入计划' } as Record<string, string>)[status || ''] || '尚未核对'
}

export function ruleBusinessGuidance(code?: string): { scenario: string; prerequisite: string } {
  switch (code) {
    case 'DATA_NULL_RATE_HIGH': return { scenario: '业务上要求填写的字段，例如客户编号、金额或必要时间。', prerequisite: '先区分必填与可选字段；阈值应符合业务要求，不能对所有可选字段套用同一空值率。' }
    case 'DATA_UNIQUE_VIOLATION': return { scenario: '业务上必须唯一的标识，如主键、订单号。', prerequisite: '先确认字段确实要求唯一；姓名、金额等可重复字段不适用。默认仅检查主键。' }
    case 'DATA_FK_ORPHAN': return { scenario: '已登记关联关系，且引用值应在目标表存在的场景。', prerequisite: '先确认元数据关系与业务引用约束正确；抽样检查不证明所有引用都完整。' }
    case 'DATA_STALE_TABLE': return { scenario: '业务上有明确更新周期、需要监测数据新鲜度的表。', prerequisite: '核对可读时间字段是否代表实际更新时间，而不是仅代表创建时间；没有可用字段时不适用。' }
    case 'TIME_TABLE_NO_UPDATE': return { scenario: '提示采集估算行数为零的普通表，供人工排查。', prerequisite: '估算行数可能不准确，不能据此认定表为空或长期未更新。' }
    case 'TIME_SNAPSHOT_EXPIRED': return { scenario: '需要定期重新采集结构的元数据快照。', prerequisite: '阈值对应元数据采集周期，不代表业务表数据更新时间。' }
    case 'ACCU_ENUM_ANOMALY': return { scenario: '取值集合应有限的状态、分类等疑似枚举字段。', prerequisite: '先确认字段是枚举；自由文本和连续数值不能因取值多就认定异常。' }
    case 'CONS_CROSS_TABLE_TYPE_MISMATCH':
    case 'CONS_CROSS_TABLE_COMMENT_CONFLICT': return { scenario: '同一数据源内，同名字段应表达同一业务概念。', prerequisite: '先确认同名是否同义；跨表合法差异需要人工核对，不能仅凭同名就修改来源。' }
    case 'TRACE_FK_MISSING': return { scenario: '按命名约定应有业务关联、但未登记关系的字段。', prerequisite: '确认 *_id 字段确实用于引用其他对象；命名提示不是关系存在的证明。' }
    case 'TRACE_ISOLATED_TABLE': return { scenario: '希望检查表是否缺少已登记的关联关系。', prerequisite: '独立使用的表可能合理；是否需要补充关系应结合业务用途判断。' }
    case 'COMP_PRIMARY_KEY_MISSING': return { scenario: '需要稳定行标识、唯一定位记录的业务表。', prerequisite: '确认主键要求；缺少主键是结构问题，不等于已经发现重复记录。' }
    default: return { scenario: '检查元数据结构与说明，帮助识别规范和可理解性问题。', prerequisite: '结合对象业务含义核对；发现问题后补齐来源或治理证据，不能用修改状态代替修复。' }
  }
}

/** 历史记录缺少参数时不以当前默认值补造旧条件。 */
export function describeRecordedRuleCondition(code?: string, parameters: Record<string, unknown> = {}) {
  const required = ({ DATA_NULL_RATE_HIGH: 'threshold', DATA_STALE_TABLE: 'staleDays', TIME_SNAPSHOT_EXPIRED: 'maxAgeDays', ACCU_ENUM_ANOMALY: 'maxDistinctValues', DATA_UNIQUE_VIOLATION: 'primaryKeyOnly' } as Record<string, string>)[code || '']
  if (required && (!Object.prototype.hasOwnProperty.call(parameters, required) || parameters[required] == null)) return '当时未记录完整参数，无法还原异常条件。'
  if (!Object.keys(parameters).length) return '该修订没有参数记录；固定条件需结合当时的检查项解释。'
  return describeRuleCondition(code, parameters)
}

/** 列表只保留一行决策信息；证据限制与业务前提在详情中展开。 */
export function briefRuleCondition(code?: string, parameters: Record<string, unknown> = {}) {
  switch (code) {
    case 'DATA_NULL_RATE_HIGH': return `空值率 > ${((Number(parameters.threshold ?? 0.3)) * 100).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}%（抽样）`
    case 'DATA_STALE_TABLE': return `时间字段超过 ${parameters.staleDays ?? 30} 天未更新`
    case 'DATA_UNIQUE_VIOLATION': return parameters.primaryKeyOnly === false ? '声明唯一的字段出现重复值（抽样）' : '主键出现重复值（抽样）'
    case 'DATA_FK_ORPHAN': return '引用值在目标表中不存在（抽样）'
    case 'COMP_PRIMARY_KEY_MISSING': return '表未定义主键'
    case 'COMP_TABLE_COMMENT_MISSING': return '表说明为空'
    case 'COMP_COLUMN_COMMENT_MISSING': return '字段说明为空'
    case 'TIME_TABLE_NO_UPDATE': return '采集估算行数为零'
    case 'TIME_SNAPSHOT_EXPIRED': return `快照采集时间超过 ${parameters.maxAgeDays ?? 7} 天`
    case 'ACCU_ENUM_ANOMALY': return `枚举去重值数量 > ${parameters.maxDistinctValues ?? 50}`
    case 'TRACE_FK_MISSING': return '疑似引用字段未登记关系'
    case 'TRACE_ISOLATED_TABLE': return '表未参与已登记关系'
    case 'CONS_CROSS_TABLE_TYPE_MISMATCH': return '同名字段的基础类型不一致'
    case 'CONS_CROSS_TABLE_COMMENT_CONFLICT': return '同名字段的业务说明不一致'
    case 'ACCU_TYPE_NAME_MISMATCH': return '字段类型与命名提示不匹配'
    default: return '查看详情了解检查条件'
  }
}
