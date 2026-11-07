// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import LoginView from './LoginView.vue'

const api = vi.hoisted(() => ({
  captcha: vi.fn(),
  login: vi.fn(),
  replace: vi.fn(),
  warning: vi.fn(),
  error: vi.fn(),
  success: vi.fn(),
}))
const auth = vi.hoisted(() => ({ token: '', login: api.login }))
vi.mock('vue-router', () => ({ useRouter: () => ({ replace: api.replace }) }))
vi.mock('../stores/auth', () => ({ useAuthStore: () => auth }))
vi.mock('../api/auth', () => ({ getCaptcha: api.captcha }))
vi.mock('element-plus', () => ({
  ElMessage: { warning: api.warning, error: api.error, success: api.success },
}))

const wrappers: VueWrapper[] = []
async function render() {
  const wrapper = mount(LoginView, { attachTo: document.body })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
beforeEach(() => {
  vi.resetAllMocks()
  api.captcha.mockResolvedValue({
    data: { captchaKey: 'captcha-test-key', captchaImage: 'data:image/png;base64,TEST' },
  })
  auth.token = ''
  api.login.mockImplementation(async () => {
    auth.token = 'fresh-session'
    return { data: { token: 'fresh-session' } }
  })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.replaceChildren()
})

describe('登录表单', () => {
  it('三个必填字段均有原生标签，密码显示切换保持原值', async () => {
    const wrapper = await render()
    const fields = wrapper.findAll('input[required]')
    expect(fields.map((field) => wrapper.find(`label[for="${field.attributes('id')}"]`).text())).toEqual([
      '账号',
      '密码',
      '验证码',
    ])
    await wrapper.find('#login-password').setValue('keep me')
    await wrapper.find('button[aria-label="显示密码"]').trigger('click')
    expect(wrapper.find('#login-password').attributes('type')).toBe('text')
    await wrapper.find('button[aria-label="隐藏密码"]').trigger('click')
    expect((wrapper.find('#login-password').element as HTMLInputElement).value).toBe('keep me')
  })
  // 用户用键盘移到显隐按钮后，角色表情仍要与密码的实际可见状态一致。
  it('密码获得焦点时闭眼，显示密码期间移开焦点也保持闭眼', async () => {
    const wrapper = await render()
    const password = wrapper.get('#login-password')
    const scene = wrapper.get('.login-characters')
    await password.trigger('focus')
    expect(scene.classes()).toContain('is-concealed')
    await password.trigger('blur')
    expect(scene.classes()).not.toContain('is-concealed')
    await wrapper.get('button[aria-label="显示密码"]').trigger('click')
    await wrapper.get('#login-captcha').trigger('focus')
    expect(scene.classes()).toContain('is-concealed')
    await wrapper.get('button[aria-label="隐藏密码"]').trigger('click')
    expect(scene.classes()).not.toContain('is-concealed')
  })
  it('拒绝登录后保留账号，清空密码和旧验证码并恢复键盘焦点', async () => {
    api.login.mockRejectedValue({ response: { data: { message: '账号或密码错误' } } })
    const wrapper = await render()
    await wrapper.find('#login-username').setValue('reader')
    await wrapper.find('#login-password').setValue('wrong')
    await wrapper.find('#login-captcha').setValue('K7M2')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toBe('账号或密码错误')
    expect((wrapper.find('#login-username').element as HTMLInputElement).value).toBe('reader')
    expect((wrapper.find('#login-password').element as HTMLInputElement).value).toBe('')
    expect((wrapper.find('#login-captcha').element as HTMLInputElement).value).toBe('')
    expect(document.activeElement).toBe(wrapper.find('#login-password').element)
    expect(api.captcha).toHaveBeenCalledTimes(2)
  })
  it('仍使用真实验证码 key 和完整密码提交，成功后交给统一默认入口选择', async () => {
    const wrapper = await render()
    await wrapper.find('input[autocomplete="username"]').setValue(' reader ')
    await wrapper.find('input[autocomplete="current-password"]').setValue(' secret ')
    await wrapper.find('#login-captcha').setValue('K7M2')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(api.login).toHaveBeenCalledWith(
      { username: 'reader', password: ' secret ', captchaKey: 'captcha-test-key', captchaCode: 'K7M2' },
      expect.any(Function),
    )
    expect(api.login.mock.calls[0][1]()).toBe(true)
    wrapper.unmount()
    expect(api.login.mock.calls[0][1]()).toBe(false)
    expect(api.replace).toHaveBeenCalledWith('/')
  })
  it('登录成功回调属于旧会话时不跳转或提示新账号登录成功', async () => {
    api.login.mockResolvedValue({ data: { token: 'older-session' } })
    auth.token = 'newer-session'
    const wrapper = await render()
    await wrapper.find('input[autocomplete="username"]').setValue('reader')
    await wrapper.find('input[autocomplete="current-password"]').setValue('Secret123')
    await wrapper.find('#login-captcha').setValue('K7M2')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(api.replace).not.toHaveBeenCalled()
    expect(api.success).not.toHaveBeenCalled()
  })
  it('账号已切换时旧登录失败不能清空新输入或刷新新验证码', async () => {
    let reject!: (cause: unknown) => void
    api.login.mockReturnValue(
      new Promise((_resolve, failure) => {
        reject = failure
      }),
    )
    const wrapper = await render()
    await wrapper.find('#login-username').setValue('reader')
    await wrapper.find('#login-password').setValue('previous-input')
    await wrapper.find('#login-captcha').setValue('K7M2')
    await wrapper.find('form').trigger('submit')
    auth.token = 'new-session'
    await wrapper.find('#login-password').setValue('new-input')
    reject(new Error('旧请求断开'))
    await flushPromises()
    expect(api.error).not.toHaveBeenCalled()
    expect(api.captcha).toHaveBeenCalledTimes(1)
    expect((wrapper.find('#login-password').element as HTMLInputElement).value).toBe('new-input')
  })
  it('验证码失败明确提示，可用键盘按钮重试', async () => {
    api.captcha.mockRejectedValueOnce(new Error('服务不可用'))
    const wrapper = await render()
    expect(wrapper.find('#captcha-error').text()).toContain('验证码加载失败')
    expect(api.login).not.toHaveBeenCalled()
    await wrapper.find('button[aria-label="刷新验证码"]').trigger('click')
    await flushPromises()
    expect(api.captcha).toHaveBeenCalledTimes(2)
    expect(wrapper.find('#captcha-error').exists()).toBe(false)
  })
})
