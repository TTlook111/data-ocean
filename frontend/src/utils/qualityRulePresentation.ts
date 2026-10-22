import { severityLabel } from './enumLabels'

export interface QualityRuleParameterDescription {
  key: string
  label: string
  type: 'INTEGER' | 'NUMBER' | 'BOOLEAN' | string
}

/** 列表、详情和表单共用的字段标签。 */
export const RULE_LABELS = {
  ruleName: '规则名称',
  templateCode: '检查类型',
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
