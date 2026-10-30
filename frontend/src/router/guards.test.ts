import { beforeEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { setupRouterGuards } from './guards'

const state = vi.hoisted(() => ({
  auth: { token: 'test-session', user: { passwordChanged: true }, fetchUserInfo: vi.fn() },
  context: { datasourceId: 1 as number | undefined, currentSnapshot: undefined },
  iam: { loaded: true, errorMessage: null as string | null, hasAnyAdminCapability: true, queryUse: false,
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
  state.iam.queryUse = false
  state.iam.load.mockResolvedValue(undefined)
  state.iam.hasGlobal.mockReturnValue(false)
  state.iam.canOnDatasource.mockImplementation((code: string, id?: number) => code === 'knowledge:view' && id === 2)
  state.iam.datasourcesWithFunction.mockImplementation((code: string) => code === 'knowledge:view' ? [2] : [])
  state.context.datasourceId = 1
})

function testRouter() {
  const empty = { render: () => null }
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', redirect: '/query' },
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
  state.iam.queryUse = true
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

it.each(['/', '/login'])('无问数权的已登录账号从%s进入实际负责的工作区',async(path)=>{
  const router=testRouter();await router.push(path)
  expect(router.currentRoute.value.path).toBe('/admin/semantics/knowledge')
  expect(router.currentRoute.value.query.datasourceId).toBe('2')
  expect(state.iam.load).toHaveBeenCalledWith(true)
})
it.each(['/', '/login', '/admin'])('无任何有效工作区或问数入口时%s落到个人资料',async(path)=>{
  state.iam.hasAnyAdminCapability=false
  state.iam.canOnDatasource.mockReturnValue(false)
  state.iam.datasourcesWithFunction.mockReturnValue([])
  const router=testRouter();await router.push(path)
  expect(router.currentRoute.value.path).toBe('/profile')
})
it.each(['/', '/login'])('有问数权时%s保留问数优先，不以后台管理权推断数据授权',async(path)=>{
  state.iam.queryUse=true
  const router=testRouter();await router.push(path)
  expect(router.currentRoute.value.path).toBe('/query')
})
it.each(['/', '/login', '/admin'])('读取默认入口时%s不沿用已撤销的能力缓存',async(path)=>{
  state.iam.queryUse=true
  state.iam.load.mockImplementation(async(force?:boolean)=>{
    if(force){state.iam.queryUse=false;state.iam.hasAnyAdminCapability=false;state.iam.datasourcesWithFunction.mockReturnValue([])}
  })
  const router=testRouter();await router.push(path)
  expect(router.currentRoute.value.path).toBe('/profile')
})
it('能力读取失败不把菜单的fail-open可见性用于默认入口',async()=>{
  state.iam.queryUse=true
  state.iam.load.mockImplementation(async()=>{state.iam.loaded=false;state.iam.errorMessage='服务不可用'})
  const router=testRouter();await router.push('/')
  expect(router.currentRoute.value.path).toBe('/profile')
})
it.each(['/', '/login', '/admin'])('曾成功加载后%s刷新失败也不沿用旧入口',async(path)=>{
  state.iam.queryUse=true
  state.iam.load.mockImplementation(async()=>{state.iam.errorMessage='本次刷新失败'})
  const router=testRouter();await router.push(path)
  expect(router.currentRoute.value.path).toBe('/profile')
})
it.each(['/', '/admin'])('只有维护能力但无有效页面查看入口时%s不进入假工作区',async(path)=>{
  state.iam.hasAnyAdminCapability=true
  state.iam.canOnDatasource.mockReturnValue(false)
  state.iam.datasourcesWithFunction.mockReturnValue([])
  const router=testRouter();await router.push(path)
  expect(router.currentRoute.value.path).toBe('/profile')
})
it('首登从login入口也先改密，不先进入问数',async()=>{
  state.auth.user.passwordChanged=false
  const router=testRouter();await router.push('/login')
  expect(router.currentRoute.value.fullPath).toBe('/change-password?forced=1')
})
it('默认入口能力请求期间登出，迟到结果不恢复原页面',async()=>{
  state.iam.load.mockImplementation(async()=>{state.auth.token=''})
  const router=testRouter();await router.push('/')
  expect(router.currentRoute.value.path).toBe('/login')
})
it('能力读取期间换账号时重新选择新账号入口',async()=>{
  state.iam.load.mockImplementationOnce(async()=>{state.auth.token='next-session';state.iam.queryUse=true})
  const router=testRouter();await router.push('/')
  expect(router.currentRoute.value.path).toBe('/query')
  expect(state.auth.fetchUserInfo).toHaveBeenCalledTimes(2)
})
it.each([['/',false],['/',true],['/admin',false],['/admin',true]] as const)('入口%s迟到回包不能覆盖后续主动导航（换账号：%s）',async(path,switchAccount)=>{
  let finish!:()=>void
  state.iam.load.mockImplementationOnce(()=>new Promise<void>(resolve=>{finish=resolve}))
  const router=testRouter(),old=router.push(path)
  await vi.waitFor(()=>expect(state.iam.load).toHaveBeenCalledTimes(1))
  if(switchAccount) state.auth.token='next-session'
  await router.push('/profile')
  finish();await old
  expect(router.currentRoute.value.path).toBe('/profile')
  expect(state.iam.load).toHaveBeenCalledTimes(1)
})
it('资料初始化迟到也不能覆盖新账号已经完成的导航',async()=>{
  let finish!:()=>void
  state.auth.fetchUserInfo.mockImplementationOnce(()=>new Promise<void>(resolve=>{finish=resolve}))
  const router=testRouter(),old=router.push('/')
  await vi.waitFor(()=>expect(state.auth.fetchUserInfo).toHaveBeenCalledTimes(1))
  state.auth.token='next-session';await router.push('/profile')
  finish();await old
  expect(router.currentRoute.value.path).toBe('/profile')
  expect(state.auth.fetchUserInfo).toHaveBeenCalledTimes(2)
  expect(state.iam.load).not.toHaveBeenCalled()
})
