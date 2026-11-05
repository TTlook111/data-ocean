// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import Sidebar from './QuerySidebar.vue'

vi.mock('../../stores/iamS1', () => ({ useIamS1Store: () => ({ load: vi.fn() }) }))
const wrappers: VueWrapper[] = []
function render(keyword = '', canEnterAdmin = true) {
  const wrapper = mount(Sidebar, {
    attachTo: document.body,
    props: { datasourceSessions: [], activeSessionId: undefined, keyword, displayName: '测试用户', roleText: '当前账号', canEnterAdmin, mobileHistoryOpen: false },
    global: { plugins: [ElementPlus], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
  wrappers.push(wrapper)
  return wrapper
}
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()) })

describe('会话侧栏搜索与账户菜单', () => {
  it('展开搜索立即聚焦，Escape 清除筛选并把焦点交回搜索开关', async () => {
    const wrapper = render()
    await wrapper.get('button[aria-label="搜索历史会话"]').trigger('click')
    const input = wrapper.get('input[type="search"]')
    expect(document.activeElement).toBe(input.element)
    await input.setValue('订单')
    expect(wrapper.emitted('update:keyword')?.at(-1)).toEqual(['订单'])
    await input.trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('input[type="search"]').exists()).toBe(false)
    expect(wrapper.emitted('update:keyword')?.at(-1)).toEqual([''])
    expect(document.activeElement).toBe(wrapper.get('button[aria-label="搜索历史会话"]').element)
  })

  it('已有或外部恢复的关键词始终有可见的筛选输入框，按钮关闭时清除筛选', async () => {
    const wrapper = render('已有关键词')
    expect(wrapper.get('input').element.value).toBe('已有关键词')
    await wrapper.get('button[aria-label="关闭会话搜索"]').trigger('click')
    expect(wrapper.emitted('update:keyword')?.at(-1)).toEqual([''])
    await wrapper.setProps({ keyword: '恢复的条件' })
    expect(wrapper.get('input').element.value).toBe('恢复的条件')
  })

  it('菜单 Escape 关闭后恢复触发器焦点，菜单命令保持原事件合同', async () => {
    const wrapper = render()
    const trigger = wrapper.get('button[aria-label="打开用户菜单"]')
    expect(trigger.attributes('aria-haspopup')).toBe('menu')
    await trigger.trigger('click')
    await flushPromises()
    const menu = document.querySelector<HTMLElement>('.query-user-menu-popper [role="menu"]')!
    expect(menu).not.toBeNull()
    menu.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(trigger.attributes('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(trigger.element)
    await trigger.trigger('click')
    await flushPromises()
    const profile = [...document.querySelectorAll<HTMLElement>('.query-user-menu-popper [role="menuitem"]')].find(item => item.textContent?.includes('个人资料'))!
    profile.click()
    await flushPromises()
    expect(wrapper.emitted('user-command')).toEqual([['profile']])
  })

  it('没有后台入口能力时不提供后台管理命令', async () => {
    const wrapper = render('', false)
    await wrapper.get('button[aria-label="打开用户菜单"]').trigger('click')
    await flushPromises()
    expect(document.querySelector('.query-user-menu-popper')?.textContent).not.toContain('后台管理')
  })
})
