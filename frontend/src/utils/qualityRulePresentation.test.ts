import { describe, expect, it } from 'vitest'
import {
  RULE_LABELS,
  ruleEvidenceLimit,
  ruleBusinessGuidance,
  ruleExecutionLabel,
  qualityBatchLabel,
  describeRuleCondition,
  briefRuleCondition,
  describeRecordedRuleCondition,
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
      templateCode: '检查项',
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

describe('检查证据边界', () => {
  it('区分执行完成和质量通过，空样本与失败不得伪装成通过', () => {
    expect(ruleExecutionLabel('SUCCESS', 2)).toBe('执行完成 · 发现问题')
    expect(ruleExecutionLabel('NOT_APPLICABLE')).toBe('不适用')
    expect(ruleExecutionLabel('FAILURE')).toBe('执行失败')
    expect(ruleExecutionLabel('NOT_EXECUTED')).toBe('未执行')
    expect(qualityBatchLabel('PARTIAL')).toBe('批次部分失败')
  })
  it('分别说明抽样、估算行数与时间字段前提', () => {
    expect(ruleEvidenceLimit('DATA_NULL_RATE_HIGH')).toContain('100,000')
    expect(ruleEvidenceLimit('DATA_FK_ORPHAN')).toContain('引用端')
    expect(ruleEvidenceLimit('TIME_TABLE_NO_UPDATE')).toContain('不能证明')
    expect(ruleEvidenceLimit('DATA_STALE_TABLE')).toContain('不适用')
    expect(describeRuleCondition('DATA_UNIQUE_VIOLATION')).toContain('主键字段')
  })
})

describe('检查项业务适用前提', () => {
  it('区分元数据采集周期和业务更新时间，唯一性要求先确认业务语义', () => {
    expect(ruleBusinessGuidance('TIME_SNAPSHOT_EXPIRED').prerequisite).toContain('不代表业务表数据更新时间')
    expect(ruleBusinessGuidance('DATA_STALE_TABLE').prerequisite).toContain('实际更新时间')
    expect(ruleBusinessGuidance('DATA_UNIQUE_VIOLATION').prerequisite).toContain('姓名、金额等可重复字段不适用')
  })
})

it('历史参数不完整时不使用当前默认值补造旧条件', () => {
  expect(describeRecordedRuleCondition('DATA_NULL_RATE_HIGH', {})).toContain('无法还原')
  expect(describeRecordedRuleCondition('DATA_STALE_TABLE', { staleDays: 12 })).toContain('12 天')
  expect(describeRecordedRuleCondition('COMP_PRIMARY_KEY_MISSING', {})).toContain('没有参数记录')
})

it('列表条件简短且不把结构检查改成数据检查', () => {
  expect(briefRuleCondition('COMP_PRIMARY_KEY_MISSING')).toBe('表未定义主键')
  expect(briefRuleCondition('COMP_TABLE_COMMENT_MISSING')).toBe('表说明为空')
  expect(briefRuleCondition('DATA_NULL_RATE_HIGH', { threshold: 0.05 })).toContain('5%')
})
