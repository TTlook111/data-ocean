// @vitest-environment happy-dom
import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, {ElMessageBox} from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
const api = vi.hoisted(() => ({
  me: vi.fn(),
  updateProfile: vi.fn(),
  changePassword: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  replace: vi.fn(),
}))
const route = vi.hoisted(() => ({ query: {} as Record<string, unknown> }))
vi.mock('../../api/http', () => ({ http: {} }))
vi.mock('../../api/auth', () => api)
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ replace: api.replace }) }))
import Password from './ChangePassword.vue'
import Profile from './ProfileView.vue'
import { useAuthStore } from '../../stores/auth'
import { useIamS1Store } from '../../stores/iamS1'
import { pendingGovernanceWrites } from '../../composables/governanceWriteTracker'
const defer = () => {
  let resolve!: (x: any) => void
  return { promise: new Promise((r) => (resolve = r)), resolve: (x: any) => resolve(x) }
}
const person = (name = '合成人员') => ({
  code: 200,
  data: {
    id: 1,
    username: 'fixture',
    realName: name,
    email: 'demo@example.test',
    phone: '',
    passwordChanged: true,
  },
})
let wrappers: any[] = []
async function page(component: any) {
  const w = mount(component, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        AccountPage: {
          props: ['title', 'description', 'eyebrow'],
          template: '<main><h1>{{title}}</h1><p>{{description}}</p><slot name="navigation"/><slot/></main>',
        },
        RouterLink: { props: ['to'], template: '<a :href="to"><slot/></a>' },
        teleport: true,
      },
    },
  })
  wrappers.push(w)
  await flushPromises()
  return w
}
const button = (w: any, name: string) => w.findAll('button').find((b: any) => b.text() === name)!
async function passwordForm(w: any) {
  const i = w.findAll('input')
  await i[0].setValue(' SyntheticOld123 ')
  await i[1].setValue('SyntheticNew123')
  await i[2].setValue('SyntheticNew123')
}
beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  useAuthStore().token = 'A'
  pendingGovernanceWrites.value = []
  Object.values(api).forEach((f) => f.mockReset())
  api.me.mockResolvedValue(person())
  api.updateProfile.mockResolvedValue({ code: 200, data: null })
  api.changePassword.mockResolvedValue({ code: 200, data: null })
  api.logout.mockResolvedValue({ code: 200, data: null })
  route.query = {}
  const i = useIamS1Store()
  i.loaded = true
  i.snapshot = {
    protocolVersion: 'IAM-SIMPLE-1',
    userId: 1,
    systemAdmin: false,
    globalFunctions: [],
    datasourceCapabilities: [],
    queryUse: false,
    viewSql: false,
    export: false,
    permissionRevision: 1,
  }
})
afterEach(() => {
  wrappers.forEach((w) => w.unmount())
  wrappers = []
  vi.restoreAllMocks()
})
describe('本人账号安全和草稿', () => {
  it('不合法密码不发请求，既有密码规则不降级', async () => {
    const w = await page(Password)
    await button(w, '确认修改并重新登录').trigger('click')
    await flushPromises()
    expect(api.changePassword).not.toHaveBeenCalled()
    expect(w.text()).toContain('请输入旧密码')
  })
  it('重复提交一次，保留原密码空格，成功退出重新登录', async () => {
    const d = defer()
    api.changePassword.mockReturnValue(d.promise)
    const w = await page(Password)
    await passwordForm(w)
    await button(w, '确认修改并重新登录').trigger('click')
    await button(w, '确认修改并重新登录').trigger('click')
    await flushPromises()
    expect(api.changePassword).toHaveBeenCalledExactlyOnceWith({
      oldPassword: ' SyntheticOld123 ',
      newPassword: 'SyntheticNew123',
    })
    d.resolve({ code: 200, data: null })
    await flushPromises()
    expect(api.logout).toHaveBeenCalledOnce()
    expect(api.replace).toHaveBeenCalledWith('/login')
    expect(w.findAll('input').every((i: any) => i.element.value === '')).toBe(true)
  })
  it('A改密迟到成功不能退出后来B账号', async () => {
    const d = defer()
    api.changePassword.mockReturnValue(d.promise)
    const w = await page(Password)
    await passwordForm(w)
    await button(w, '确认修改并重新登录').trigger('click')
    await flushPromises()
    useAuthStore().token = 'B'
    d.resolve({ code: 200, data: null })
    await flushPromises()
    expect(api.logout).not.toHaveBeenCalled()
    expect(useAuthStore().token).toBe('B')
    expect(api.replace).not.toHaveBeenCalled()
  })
  it('改密409清秘密、保留中文错误，不自动重发', async () => {
    api.changePassword.mockRejectedValue({ response: { status: 409, data: { message: '合成凭据冲突' } } })
    const w = await page(Password)
    await passwordForm(w)
    await button(w, '确认修改并重新登录').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('合成凭据冲突')
    expect(w.findAll('input').every((i: any) => i.element.value === '')).toBe(true)
    expect(api.changePassword).toHaveBeenCalledOnce()
  })
  it('改密未知回执不会伪成功或退出，阻止重复写', async () => {
    api.changePassword.mockResolvedValue({})
    const w = await page(Password)
    await passwordForm(w)
    await button(w, '确认修改并重新登录').trigger('click')
    await flushPromises()
    expect(api.logout).not.toHaveBeenCalled()
    expect(w.text()).toContain('结果未知')
    await passwordForm(w)
    await button(w, '确认修改并重新登录').trigger('click')
    await flushPromises()
    expect(api.changePassword).toHaveBeenCalledOnce()
  })
  it('强制改密不显示离开设置入口', async () => {
    route.query = { forced: '1' }
    const w = await page(Password)
    expect(w.text()).toContain('首次登录')
    expect(w.findAll('a').some(a=>['/query','/profile'].includes(a.attributes('href') || ''))).toBe(false)
    expect(button(w,'退出并重新登录')).toBeDefined()
  })
  it('资料失败有独立重试，不能把空白资料保存回去', async () => {
    api.me.mockRejectedValue(new Error('合成读取失败'))
    const w = await page(Profile)
    expect(w.text()).toContain('合成读取失败')
    expect(w.find('.profile-loaded').attributes('hidden')).toBeDefined()
    await button(w, '保存修改').trigger('click')
    await flushPromises()
    expect(api.updateProfile).not.toHaveBeenCalled()
  })
  it('A资料迟到回包不能覆盖新账号资料', async () => {
    const d = defer()
    api.me.mockReturnValueOnce(d.promise).mockResolvedValue(person('后来B资料'))
    const w = await page(Profile)
    useAuthStore().token = 'B'
    await flushPromises()
    d.resolve(person('原A资料'))
    await flushPromises()
    expect(w.findAll<HTMLInputElement>('input')[1].element.value).toBe('后来B资料')
  })
  it('重读保留人工编辑，未知能力不造业务角色或入口', async () => {
    const w = await page(Profile)
    await w.findAll('input')[1].setValue('人工姓名')
    await button(w, '重读资料').trigger('click')
    await flushPromises()
    expect(w.findAll<HTMLInputElement>('input')[1].element.value).toBe('人工姓名')
    expect(w.text()).toContain('人工编辑已保留')
    expect(w.find('a[href="/query"]').exists()).toBe(false)
  })
  it('资料保存双击一次，409保留原输入', async () => {
    const d = defer()
    api.updateProfile.mockReturnValue(d.promise)
    const w = await page(Profile)
    await w.findAll('input')[1].setValue('人工姓名')
    await button(w, '保存修改').trigger('click')
    await button(w, '保存修改').trigger('click')
    await flushPromises()
    expect(api.updateProfile).toHaveBeenCalledExactlyOnceWith({
      realName: '人工姓名',
      email: 'demo@example.test',
      phone: '',
    })
    d.resolve(Promise.reject({ response: { status: 409, data: { message: '合成资料冲突' } } }))
    await flushPromises()
    expect(w.findAll<HTMLInputElement>('input')[1].element.value).toBe('人工姓名')
    expect(w.text()).toContain('合成资料冲突')
  })
  it('旧账号资料保存成功后不触发后来账号的跟进读取', async () => {
    const d = defer()
    api.updateProfile.mockReturnValue(d.promise)
    const w = await page(Profile)
    await button(w, '保存修改').trigger('click')
    await flushPromises()
    useAuthStore().token = 'B'
    await flushPromises()
    const calls = api.me.mock.calls.length
    d.resolve({ code: 200, data: null })
    await flushPromises()
    expect(api.me).toHaveBeenCalledTimes(calls)
    expect(w.text()).not.toContain('个人资料已更新')
  })
 it('强制改密结果未知仍能显式安全退出核对，不能绕过改密进入业务',async()=>{
   route.query={forced:'1'};api.changePassword.mockResolvedValue({})
   const w=await page(Password);await passwordForm(w);await button(w,'确认修改并重新登录').trigger('click');await flushPromises()
   expect(w.text()).toContain('结果未知');await button(w,'退出并重新登录').trigger('click');await flushPromises()
   expect(api.logout).toHaveBeenCalledOnce();expect(api.replace).toHaveBeenCalledWith('/login')
 })
 it('A显示密码后切B，B输入默认隐藏且原秘密清空',async()=>{
   const w=await page(Password);await passwordForm(w)
   await w.find('.el-input__password').trigger('click');expect(w.find<HTMLInputElement>('input').element.type).toBe('text')
   useAuthStore().token='B';await flushPromises()
   expect(w.find<HTMLInputElement>('input').element.type).toBe('password');expect(w.find<HTMLInputElement>('input').element.value).toBe('')
 })
 it.each([['密码',Password],['资料',Profile]])('%s异步验证未完成时切账号，零写请求',async(label,component)=>{
   const w=await page(component);if(label==='密码')await passwordForm(w)
   const click=button(w,label==='密码'?'确认修改并重新登录':'保存修改').trigger('click')
   expect((w.vm as any).$.setupState[label==='密码'?'loading':'saving']).toBe(true)
   useAuthStore().token='B';await click;await flushPromises()
   expect(api.changePassword).not.toHaveBeenCalled();expect(api.updateProfile).not.toHaveBeenCalled()
 })
 it('资料冲突后可明确放弃草稿重读实际服务端事实',async()=>{
   vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as any)
   const w=await page(Profile);await w.findAll('input')[1].setValue('未保存人工输入')
   api.me.mockResolvedValue(person('服务端实际姓名'))
   await button(w,'放弃草稿并重读').trigger('click');await flushPromises()
   expect(w.findAll<HTMLInputElement>('input')[1].element.value).toBe('服务端实际姓名')
   expect(api.updateProfile).not.toHaveBeenCalled()
 })

 it('显式退出等待期间冻结密码表单，不并发发起改密',async()=>{
   const d=defer();api.logout.mockReturnValue(d.promise)
   const w=await page(Password);await passwordForm(w)
   await button(w,'退出并重新登录').trigger('click')
   expect(w.findAll('input').every((i:any)=>i.attributes('disabled')!==undefined)).toBe(true)
   await button(w,'确认修改并重新登录').trigger('click');await flushPromises()
   expect(api.changePassword).not.toHaveBeenCalled();d.resolve({code:200,data:null});await flushPromises()
 })

})
