import { describe, expect, it } from 'vitest'
import {
  RULE_LABELS,
  describeRuleCondition,
  describeRuleImpact,
  describeRuleScope,
  ruleScopeLabel,
  ruleTargetTypeLabel,
  sortRuleScopes,
} from './qualityRulePresentation'

describe('quality rule presentation', () => {
  it('explains empty estimated tables without claiming freshness was measured', () => {
    expect(describeRuleCondition('TIME_TABLE_NO_UPDATE')).toContain('不能证明长期没有数据更新')
  })

  it('renders configured parameters as user-facing conditions', () => {
    expect(describeRuleCondition('DATA_STALE_TABLE', { staleDays: 14 })).toContain('超过 14 天未更新')
    expect(describeRuleCondition('DATA_NULL_RATE_HIGH', { threshold: 0.25 })).toContain('25%')
  })

  it('distinguishes inherited global rules in datasource view', () => {
    expect(describeRuleScope('GLOBAL', undefined, undefined, undefined, true)).toBe('全局默认 · 适用于当前数据源')
    expect(describeRuleScope('GLOBAL')).toBe('全局 · 适用于所有数据源')
  })

  it('states severity and score impact as text', () => {
    expect(describeRuleImpact('HIGH', 5)).toBe('高 · 扣 5 分')
    expect(describeRuleImpact('MEDIUM', 3)).toBe('中 · 扣 3 分')
  })

  it('uses one label set across list, detail and form', () => {
    expect(RULE_LABELS).toMatchObject({
      templateCode: '检查类型',
      scopeType: '适用范围',
      severity: '严重程度',
      deductionPoints: '扣分',
      description: '处理建议',
    })
    expect(ruleScopeLabel('COLUMN')).toBe('字段')
    expect(describeRuleScope('COLUMN', '销售库', 'orders', 'amount')).toBe('字段 · 销售库 · orders · amount')
  })

  it('sorts allowed scopes in the canonical order regardless of backend Set order', () => {
    expect(sortRuleScopes(['COLUMN', 'GLOBAL', 'TABLE', 'DATASOURCE']))
      .toEqual(['GLOBAL', 'DATASOURCE', 'TABLE', 'COLUMN'])
  })

  it('labels template target types consistently', () => {
    expect(ruleTargetTypeLabel('TABLE')).toBe('表')
    expect(ruleTargetTypeLabel('COLUMN')).toBe('字段')
    expect(ruleTargetTypeLabel('RELATION')).toBe('关系')
    expect(ruleTargetTypeLabel(undefined)).toBe('未指定')
  })
})
