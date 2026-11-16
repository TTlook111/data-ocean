// @vitest-environment happy-dom
import { beforeEach, describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
const router = vi.hoisted(() => ({ replace: vi.fn(), back: vi.fn(), resolve: vi.fn() }))
vi.mock('../../api/http', () => ({ http: {} }))
vi.mock('vue-router', () => ({ useRouter: () => router }))
import QueryGuide from './QueryGuide.vue'
import AdminGuide from './AdminGuide.vue'
import NotFound from '../NotFound.vue'
import { useIamS1Store } from '../../stores/iamS1'
import { useAuthStore } from '../../stores/auth'
function rights(queryUse = false, codes: string[] = []) {
  const iam = useIamS1Store()
  iam.loaded = true
  iam.snapshot = {
    protocolVersion: 'IAM-SIMPLE-1',
    userId: 1,
    systemAdmin: false,
    globalFunctions: codes,
    datasourceCapabilities: [],
    queryUse,
    viewSql: false,
    export: false,
    permissionRevision: 1,
  }
  return iam
}
const page = (p: any) =>
  mount(p, {
    global: {
      stubs: { RouterLink: { props: ['to'], template: '<a :data-target="JSON.stringify(to)"><slot/></a>' } },
    },
  })
beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  useAuthStore().token = 'A'
  rights()
  vi.clearAllMocks()
  window.history.replaceState({}, '')
  router.resolve.mockReturnValue({ matched: [] })
})
describe('指南和缺页导航只使用当前有权工作区', () => {
  it('指南能力未知或失败时不把导航工具fail-open当成授权', async () => {
    const i = rights(true, ['datasource:view'])
    i.errorMessage = '读取失败'
    const w = page(AdminGuide)
    await flushPromises()
    expect(w.findAll('.guide-link')).toHaveLength(0)
    expect(w.text()).not.toContain('进入有权工作区')
    w.unmount()
  })
  it('只有源查看能力的指南不显示维护审核或其他源入口', async () => {
    rights(false, ['datasource:view'])
    const w = page(AdminGuide)
    await flushPromises()
    expect(w.findAll('.guide-link')).toHaveLength(1)
    expect(w.find('.guide-link').text()).toContain('数据源')
    expect(w.text()).not.toContain('进入问数')
    w.unmount()
  })
  it('无查询权不显示进入问数动作，仍可阅读说明', async () => {
    const w = page(QueryGuide)
    await flushPromises()
    expect(w.findAll('a').some((a) => a.text() === '进入问数')).toBe(false)
    expect(w.findAll('details')).toHaveLength(4)
    w.unmount()
  })
  it('404按当前角色返回问数或合法后台，而非盲目工作台', async () => {
    rights(true)
    let w = page(NotFound)
    await flushPromises()
    expect(w.find('.primary').text()).toContain('返回问数')
    w.unmount()
    rights(false, ['admin:workbench:view'])
    w = page(NotFound)
    await flushPromises()
    expect(w.find('.primary').attributes('data-target')).toContain('/admin/workbench')
    w.unmount()
  })
  it('404无有效历史时返回资料，外部或缺页历史不再循环', async () => {
    const w = page(NotFound)
    await flushPromises()
    for (const back of ['//example.invalid', '/missing']) {
      window.history.replaceState({ back }, '')
      await w.find('button').trigger('click')
      expect(router.replace).toHaveBeenLastCalledWith({ path: '/profile' })
    }
    expect(router.back).not.toHaveBeenCalled()
    w.unmount()
  })
  it('404只对已匹配的内部业务页面返回历史', async () => {
    window.history.replaceState({ back: '/profile' }, '')
    router.resolve.mockReturnValue({ matched: [{ path: '/profile' }] })
    const w = page(NotFound)
    await flushPromises()
    await w.find('button').trigger('click')
    expect(router.back).toHaveBeenCalledOnce()
    expect(router.replace).not.toHaveBeenCalled()
    w.unmount()
  })
})
