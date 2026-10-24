// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import RuleApplicabilitySummary from './RuleApplicabilitySummary.vue'
const summary = {
  enabledCount: 2, applicableCount: 1, unsupportedCount: 0, overriddenCount: 0,
  globalCount: 1, datasourceCount: 1, notApplicableCount: 1, invalidCount: 0,
  rules: [
    { ruleId: 1, ruleName: '数据新鲜度', configVersion: 1, scopeType: 'GLOBAL' as const, status: 'NOT_APPLICABLE' as const, reason: '缺少可读时间字段', targetCount: 3, plannedTargetCount: 0 },
    { ruleId: 2, ruleName: '订单主键', configVersion: 2, scopeType: 'TABLE' as const, status: 'READY' as const, targetCount: 1, plannedTargetCount: 1 },
  ],
}
const render = (props = {}) => mount(RuleApplicabilitySummary, { props: { summary, ...props }, global: { plugins: [ElementPlus] } })
describe('规则适用性只读规划', () => {
  it('解释候选数与可参与数的差异，区别继承全局和本源专用', () => {
    const wrapper = render()
    expect(wrapper.text()).toContain('继承全局 1 · 本源专用 1')
    expect(wrapper.text()).toContain('数据新鲜度')
    expect(wrapper.text()).toContain('缺少可读时间字段')
    expect(wrapper.text()).toContain('不读取业务数据或执行检查')
  })
  it('紧凑规划摘要保留跨分页的原因入口，统计口径不随列表筛选变化', () => {
    const wrapper = render({ compact: true })
    expect(wrapper.find('details summary').text()).toContain('展开 1 条')
    expect(wrapper.find('details').text()).toContain('缺少可读时间字段')
    expect(wrapper.text()).toContain('不随下方筛选和分页变化')
  })
  it('加载和错误不当作零规则或适用结论', async () => {
    const wrapper = render({ loading: true })
    expect(wrapper.text()).toContain('正在按当前快照核对')
    expect(wrapper.find('.rule-applicability__metrics').exists()).toBe(false)
    await wrapper.setProps({ loading: false, error: '读取失败' })
    expect(wrapper.text()).toContain('读取失败')
    expect(wrapper.find('.rule-applicability__metrics').exists()).toBe(false)
  })
  it('缺失逐条明细与没有启用配置分别展示', async () => {
    const wrapper = render({ summary: { ...summary, rules: undefined } })
    expect(wrapper.text()).toContain('不能由数量推断具体原因')
    await wrapper.setProps({ summary: { ...summary, enabledCount: 0, rules: [] } })
    expect(wrapper.text()).toContain('当前没有启用的候选规则')
  })
})
