import { beforeEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { setupRouterGuards } from './guards'

const state = vi.hoisted(() => ({
  auth: { token: 'test-session', user: { passwordChanged: true }, fetchUserInfo: vi.fn() },
  context: { datasourceId: 1 as number | undefined, currentSnapshot: undefined },
  iam: { loaded: true, errorMessage: null, hasAnyAdminCapability: true,
    load: vi.fn(), hasGlobal: vi.fn(), canOnDatasource: vi.fn(), datasourcesWithFunction: vi.fn() },
}))
vi.mock('../stores/auth', () => ({ useAuthStore: () => state.auth }))
vi.mock('../stores/iamS1', () => ({ useIamS1Store: () => state.iam }))
vi.mock('../stores/adminContext', () => ({ useAdminContextStore: () => state.context }))

beforeEach(() => {
  vi.clearAllMocks()
  state.auth.token = 'test-session'
  state.auth.user = {passwordChanged:true}
  state.auth.fetchUserInfo.mockResolvedValue(undefined)
  state.iam.loaded = true
  state.iam.errorMessage = null
  state.iam.hasAnyAdminCapability = true
  state.iam.load.mockResolvedValue(undefined)
  state.iam.hasGlobal.mockReturnValue(false)
  state.iam.canOnDatasource.mockImplementation((code: string, id?: number) => code === 'knowledge:view' && id === 2)
  state.iam.datasourcesWithFunction.mockImplementation((code: string) => code === 'knowledge:view' ? [2] : [])
  state.context.datasourceId = 1
})

function testRouter() {
  const empty = { render: () => null }
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/admin', component: empty, children: [
      { path: '', redirect: '/admin/workbench' },
      { path: 'workbench', component: empty },
      { path: 'semantics/knowledge', component: empty },
    ] },
    { path: '/query', component: empty },
    { path: '/login', component: empty },
    { path: '/change-password', component: empty },
    { path: '/profile', component: empty },
  ] })
  setupRouterGuards(router)
  return router
}

it('后台默认入口进入manager实际可用知识工作区，不落入无权工作台或旧源', async () => {
  const router = testRouter()
  await router.push('/admin')
  expect(router.currentRoute.value.path).toBe('/admin/semantics/knowledge')
  expect(router.currentRoute.value.query.datasourceId).toBe('2')
})

it('后台默认入口保留当前账号对该工作区仍有权的源偏好', async () => {
  state.context.datasourceId = 3
  state.iam.canOnDatasource.mockImplementation((code: string, id?: number) => code === 'knowledge:view' && [2, 3].includes(id || 0))
  state.iam.datasourcesWithFunction.mockImplementation((code: string) => code === 'knowledge:view' ? [2, 3] : [])
  const router = testRouter()
  await router.push('/admin')
  expect(router.currentRoute.value.path).toBe('/admin/semantics/knowledge')
  expect(router.currentRoute.value.query.datasourceId).toBe('3')
})

it('确有工作台权限的账号仍默认进入工作台', async () => {
  state.iam.hasGlobal.mockImplementation((code: string) => code === 'admin:workbench:view')
  const router = testRouter()
  await router.push('/admin')
  expect(router.currentRoute.value.path).toBe('/admin/workbench')
})

it('只有问数能力的账号不因默认入口获得后台准入', async () => {
  state.iam.hasAnyAdminCapability = false
  const router = testRouter()
  await router.push('/admin')
  expect(router.currentRoute.value.path).toBe('/query')
})

it('明确工作台URL继续由页面权限提示处理，不伪装成有权访问', async () => {
  const router = testRouter()
  await router.push('/admin/workbench')
  expect(router.currentRoute.value.path).toBe('/admin/workbench')
})

it('启动读取期间当前会话失效后，不继续进入原受保护页', async()=>{
  state.auth.fetchUserInfo.mockImplementation(async()=>{state.auth.token=''})
  const router=testRouter();await router.push('/profile')
  expect(router.currentRoute.value.path).toBe('/login')
})
it('同SPA后续账号重新核验自己的资料',async()=>{
  const router=testRouter();await router.push('/profile')
  state.auth.token='second-session';await router.push('/query')
  expect(state.auth.fetchUserInfo).toHaveBeenCalledTimes(2)
})
it('首次改密要求仍进入forced路由',async()=>{
  state.auth.user.passwordChanged=false
  const router=testRouter();await router.push('/query')
  expect(router.currentRoute.value.fullPath).toBe('/change-password?forced=1')
})
