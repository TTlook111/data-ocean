// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import AdminShell from './AdminShell.vue'

const state = vi.hoisted(() => ({
  iam: { systemAdmin: true, queryUse: false },
  push: vi.fn(), logout: vi.fn(), close: vi.fn(),
  route: { path: '/admin/workbench', name: 'admin-workbench', meta: { title: '工作台', workspaceKey: 'workbench', domainKey: 'workbench', contextMode: 'none' } },
}))

vi.mock('vue-router', () => ({
  useRoute: () => state.route,
  useRouter: () => ({ push: state.push }),
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ currentUser: { realName: '验收管理员' }, user: null, logout: state.logout }) }))
vi.mock('../../stores/iamS1', () => ({ useIamS1Store: () => state.iam }))
vi.mock('./AdminDomainNav.vue', () => ({ default: { template: '<nav></nav>' } }))
vi.mock('./ScopeBar.vue', () => ({ default: { template: '<div></div>' } }))
vi.mock('../../api/notification', () => ({
  getUnreadNotificationCount: vi.fn().mockResolvedValue({ data: { count: 0 } }),
  listNotifications: vi.fn().mockResolvedValue({ data: { records: [] } }),
  markNotificationAsRead: vi.fn(), markNotificationsBatchAsRead: vi.fn(),
}))

const AccountDropdown = defineComponent({
  name: 'AccountDropdown', props: ['placement'], emits: ['command', 'visible-change'],
  setup(_, { slots, expose }) {
    expose({ handleClose: state.close })
    return () => h('div', [slots.default?.(), slots.dropdown?.()])
  },
})
const ScopeBarStub = defineComponent({
  name: 'ScopeBar',
  props: ['mode', 'showControlLabels'],
  template: '<div data-testid="scope-bar"></div>',
})
const wrappers: VueWrapper[] = []
function createShell() {
  const wrapper = mount(AdminShell, { global: { stubs: {
    AdminDomainNav: true, ScopeBar: ScopeBarStub, RouterLink: { template: '<a><slot /></a>' }, RouterView: true,
    'el-dropdown': AccountDropdown, 'el-dropdown-menu': { template: '<div><slot /></div>' },
    'el-dropdown-item': { template: '<button><slot /></button>' },
    'el-popover': { template: '<div><slot name="reference" /></div>' }, 'el-badge': { template: '<span><slot /></span>' }, 'el-empty': true,
  }, directives: { loading: {} } } })
  wrappers.push(wrapper)
  return wrapper
}

describe('admin account menu capability', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    state.logout.mockReset().mockResolvedValue(undefined)
    state.iam.systemAdmin = true
    state.iam.queryUse = false
    state.route = { path: '/admin/workbench', name: 'admin-workbench', meta: { title: '工作台', workspaceKey: 'workbench', domainKey: 'workbench', contextMode: 'none' } }
  })
  afterEach(() => { wrappers.splice(0).forEach((wrapper) => wrapper.unmount()) })

  it('shows identity without inferring query capability from an administrator label', async () => {
    const wrapper = createShell()
    expect(wrapper.find('.admin-shell__sidebar-footer').text()).toContain('验收管理员')
    expect(wrapper.find('.admin-shell__sidebar-footer').text()).toContain('系统管理员')
    expect(wrapper.find('.admin-shell__account-menu').text()).not.toContain('智能问数')
    await wrapper.findComponent(AccountDropdown).vm.$emit('command', 'query')
    expect(state.push).not.toHaveBeenCalled()
  })

  it('allows query switching only through the account menu when queryUse is returned', async () => {
    state.iam.queryUse = true
    const wrapper = createShell()
    expect(wrapper.find('.admin-shell__account-menu').text()).toContain('智能问数')
    await wrapper.findComponent(AccountDropdown).vm.$emit('command', 'query')
    expect(state.push).toHaveBeenCalledWith('/query')
    expect(state.close).toHaveBeenCalled()
  })

  it('retains profile, password and logout commands after moving the menu', async () => {
    const wrapper = createShell()
    const dropdown = wrapper.findComponent(AccountDropdown)
    await dropdown.vm.$emit('command', 'profile')
    expect(state.push).toHaveBeenLastCalledWith('/profile')
    await dropdown.vm.$emit('command', 'password')
    expect(state.push).toHaveBeenLastCalledWith('/change-password')
    await dropdown.vm.$emit('command', 'logout')
    expect(state.logout).toHaveBeenCalledOnce()
    expect(state.push).toHaveBeenLastCalledWith('/login')
  })

  it('waits for logout to clear the session before navigating to login', async () => {
    let finishLogout!: () => void
    state.logout.mockImplementation(() => new Promise<void>(resolve => { finishLogout = resolve }))
    const wrapper = createShell()
    wrapper.findComponent(AccountDropdown).vm.$emit('command', 'logout')
    await Promise.resolve()
    expect(state.logout).toHaveBeenCalledOnce()
    expect(state.push).not.toHaveBeenCalledWith('/login')
    finishLogout()
    await flushPromises()
    expect(state.push).toHaveBeenLastCalledWith('/login')
  })

  it('uses labeled scope controls and compact content spacing only for the governance overview', () => {
    state.route = { path: '/admin/governance', name: 'admin-governance', meta: { title: '治理总览', workspaceKey: 'governance', domainKey: 'governance', contextMode: 'datasource-snapshot' } }
    const overview = createShell()
    expect(overview.findComponent(ScopeBarStub).props('showControlLabels')).toBe(true)
    expect(overview.find('.admin-shell__content').classes()).toContain('admin-shell__content--compact')

    state.route = { path: '/admin/knowledge', name: 'admin-knowledge', meta: { title: '知识库', workspaceKey: 'knowledge', domainKey: 'knowledge', contextMode: 'datasource' } }
    const otherPage = createShell()
    expect(otherPage.findComponent(ScopeBarStub).props('showControlLabels')).toBe(false)
    expect(otherPage.find('.admin-shell__content').classes()).not.toContain('admin-shell__content--compact')
  })
})
