import { describe, expect, it } from 'vitest'
import { describeRuleCondition, describeRuleImpact, describeRuleScope } from './qualityRulePresentation'

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
  })
})
