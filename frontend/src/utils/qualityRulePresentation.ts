export interface QualityRuleParameterDescription {
  key: string
  label: string
  type: 'INTEGER' | 'NUMBER' | 'BOOLEAN' | string
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
  const scopeLabel = ({ DATASOURCE: '数据源', TABLE: '表', COLUMN: '字段' } as Record<string, string>)[scopeType || ''] || '未知范围'
  const path = [datasourceName, targetTableName, targetColumnName].filter(Boolean).join(' · ')
  return path ? `${scopeLabel} · ${path}` : scopeLabel
}

export function describeRuleImpact(severity: string, deductionPoints: number) {
  const label = ({ HIGH: '高', MEDIUM: '中', LOW: '低' } as Record<string, string>)[severity] || severity
  return `${label} · 扣 ${deductionPoints} 分`
}
