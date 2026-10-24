// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElInput } from 'element-plus'
import QualityCheckItemCatalog from './QualityCheckItemCatalog.vue'
import type { QualityRuleTemplate } from '../../api/admin/governance'

const items: QualityRuleTemplate[] = [
  { code: 'DATA_STALE_TABLE', name: '表数据长期未更新', dimension: 'TIMELINESS', targetType: 'TABLE', checkType: 'DATA',
    description: '检查可读时间字段对应的数据新鲜度。', allowedScopes: ['GLOBAL', 'DATASOURCE', 'TABLE'],
    parameters: [{ key: 'staleDays', label: '过期天数', type: 'INTEGER', minimum: 1, maximum: 3650, defaultValue: 30 }] },
  { code: 'COMP_TABLE_COMMENT_MISSING', name: '表注释缺失', dimension: 'COMPLETENESS', targetType: 'TABLE', checkType: 'SCHEMA',
    description: '检查表是否包含业务说明。', allowedScopes: ['GLOBAL', 'DATASOURCE', 'TABLE'], parameters: [] },
]
function render(props = {}) {
  return mount(QualityCheckItemCatalog, { props: { items, ...props }, global: { plugins: [ElementPlus] } })
}
async function inspectFirst(wrapper: ReturnType<typeof render>) {
  await wrapper.findAll('.quality-check-catalog__item')[0]!.trigger('click')
  await flushPromises()
}

describe('规则页内检查项选择与说明', () => {
  it('先浏览用途和条件，明确选择后才把检查项交给规则表单', async () => {
    const wrapper = render()
    expect(wrapper.text()).toContain('先选择一个检查项')
    await inspectFirst(wrapper)
    expect(wrapper.text()).toContain('默认异常条件')
    expect(wrapper.text()).toContain('超过 30 天未更新')
    expect(wrapper.text()).toContain('需要可读的时间字段')
    expect(wrapper.text()).toContain('整数 1–3650，默认 30')
    expect(wrapper.emitted('select')).toBeUndefined()
    await wrapper.findAll('button').find(button => button.text() === '使用此检查项')!.trigger('click')
    expect(wrapper.emitted('select')).toEqual([['DATA_STALE_TABLE']])
    wrapper.unmount()
  })
  it('只读用户可以浏览，但不能选择来创建规则，也不出现检查项增删改', async () => {
    const wrapper = render({ selectable: false, disabledReason: '没有规则维护权限' })
    await inspectFirst(wrapper)
    const select = wrapper.findAll('button').find(button => button.text() === '使用此检查项')!
    expect(select.attributes('disabled')).toBeDefined()
    await select.trigger('click')
    expect(wrapper.emitted('select')).toBeUndefined()
    expect(wrapper.text()).toContain('没有规则维护权限')
    expect(wrapper.findAll('button').some(button => /新增检查项|编辑检查项|删除检查项/.test(button.text()))).toBe(false)
    wrapper.unmount()
  })
  it('搜索支持用途和技术编码，无匹配时可恢复，不从隐藏项提交选择', async () => {
    const wrapper = render({ selectedCode: 'DATA_STALE_TABLE' })
    await wrapper.findComponent(ElInput).setValue('COMP_TABLE')
    expect(wrapper.findAll('.quality-check-catalog__item')).toHaveLength(1)
    expect(wrapper.find('.quality-check-catalog__detail').exists()).toBe(false)
    await wrapper.findComponent(ElInput).setValue('不存在')
    expect(wrapper.text()).toContain('没有符合筛选条件的检查项')
    await wrapper.findAll('button').find(button => button.text() === '清除筛选')!.trigger('click')
    expect(wrapper.findAll('.quality-check-catalog__item')).toHaveLength(2)
    wrapper.unmount()
  })
  it('编辑规则时根据当前检查项展示说明，父级更新选择后同步', async () => {
    const wrapper = render({ selectedCode: 'DATA_STALE_TABLE' })
    expect(wrapper.find('.quality-check-catalog__detail').text()).toContain('表数据长期未更新')
    await wrapper.setProps({ selectedCode: 'COMP_TABLE_COMMENT_MISSING' })
    expect(wrapper.find('.quality-check-catalog__detail').text()).toContain('使用固定条件')
    wrapper.unmount()
  })
  it('读取失败与空能力列表区分，重试交回规则页统一读取', async () => {
    const wrapper = render({ error: '检查项读取失败' })
    expect(wrapper.text()).toContain('检查项读取失败')
    await wrapper.find('.error-retry').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
    await wrapper.setProps({ error: '', items: [] })
    expect(wrapper.text()).toContain('当前没有可用的检查项')
    wrapper.unmount()
  })
})
