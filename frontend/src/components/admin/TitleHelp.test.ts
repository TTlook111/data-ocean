// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElTooltip } from 'element-plus'
import TitleHelp from './TitleHelp.vue'
import TaskPageHeader from './TaskPageHeader.vue'

const wrappers: VueWrapper[] = []
const explanation = '需要处理的数据源排在前面。打开详情查看完整条件。'

function renderHelp() {
  const wrapper = mount(TitleHelp, { attachTo: document.body, props: { label: '数据源准备情况', text: explanation } })
  wrappers.push(wrapper)
  return wrapper
}

async function settle() {
  await flushPromises()
  await vi.advanceTimersByTimeAsync(180)
  await flushPromises()
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount())
  document.body.innerHTML = ''
  vi.useRealTimers()
})

describe('标题说明的按需查看', () => {
  it('默认不展开，悬停后仍能读到完整说明', async () => {
    const wrapper = renderHelp()
    expect(wrapper.text()).not.toContain(explanation)
    const button = wrapper.get('button')
    expect(button.attributes('aria-label')).toBe('查看数据源准备情况说明')
    await button.trigger('mouseenter')
    await settle()
    expect(document.querySelector('[role="tooltip"]')?.textContent).toContain(explanation)
    expect(wrapper.getComponent(ElTooltip).props('visible')).toBe(true)
    await button.trigger('mouseleave')
    await settle()
    expect(wrapper.getComponent(ElTooltip).props('visible')).toBe(false)
  })

  it('键盘聚焦可查看，Escape 能关闭且不提交表单', async () => {
    const wrapper = renderHelp()
    const button = wrapper.get('button')
    expect(button.attributes('type')).toBe('button')
    await button.trigger('focus')
    await settle()
    expect(document.querySelector('[role="tooltip"]')?.textContent).toContain(explanation)
    await button.trigger('keydown', { key: 'Escape', code: 'Escape' })
    await settle()
    expect(wrapper.getComponent(ElTooltip).props('visible')).toBe(false)
    await button.trigger('click')
    await settle()
    expect(wrapper.getComponent(ElTooltip).props('visible')).toBe(true)
  })

  it('说明尚在等待显示时按 Escape，也不会随后重新弹出', async () => {
    const wrapper = renderHelp()
    const button = wrapper.get('button')
    await button.trigger('focus')
    await button.trigger('keydown', { key: 'Escape', code: 'Escape' })
    await settle()
    expect(wrapper.getComponent(ElTooltip).props('visible')).toBe(false)
  })

  it('页面解释默认按需显示，对象标识可以继续直接呈现', () => {
    const header = mount(TaskPageHeader, { props: { title: '工作台', description: explanation } })
    const identity = mount(TaskPageHeader, { props: { title: '订单库', description: 'MYSQL · orders', descriptionMode: 'inline' } })
    wrappers.push(header, identity)
    expect(header.text()).not.toContain(explanation)
    expect(header.get('button').attributes('aria-label')).toBe('查看工作台说明')
    expect(identity.text()).toContain('MYSQL · orders')
    expect(identity.find('button').exists()).toBe(false)
  })
})
