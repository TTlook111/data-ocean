// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import LoginView from './LoginView.vue'

const api = vi.hoisted(() => ({ captcha: vi.fn(), login: vi.fn(), replace: vi.fn(), warning: vi.fn(), error: vi.fn(), success: vi.fn() }))
const motion = vi.hoisted(() => ({ reduced: false, trackers: [] as { element: HTMLElement; property: string; to: ReturnType<typeof vi.fn> }[], cleanups: [] as (() => void)[] }))
vi.mock('vue-router', () => ({ useRouter: () => ({ replace: api.replace }) }))
vi.mock('../stores/auth', () => ({ useAuthStore: () => ({ login: api.login }) }))
vi.mock('../api/auth', () => ({ getCaptcha: api.captcha }))
vi.mock('element-plus', () => ({ ElMessage: { warning: api.warning, error: api.error, success: api.success } }))
vi.mock('gsap', () => ({ gsap: {
  matchMedia: () => ({
    add: (_query: string, setup: () => () => void) => { if (!motion.reduced) motion.cleanups.push(setup()) },
    revert: () => motion.cleanups.splice(0).forEach(cleanup => cleanup()),
  }),
  quickTo: (element: HTMLElement, property: string) => {
    const to = vi.fn()
    motion.trackers.push({ element, property, to })
    return to
  },
  set: vi.fn(), from: vi.fn(),
} }))

const wrappers: VueWrapper[] = []
const frames = new Map<number, FrameRequestCallback>()
let frameId = 0
async function render() {
  const wrapper = mount(LoginView)
  wrappers.push(wrapper)
  await flushPromises()
  wrapper.findAll('.eye').forEach(eye => {
    vi.spyOn(eye.element, 'getBoundingClientRect').mockReturnValue(new DOMRect(100, 300, 20, 20))
    const pupil = eye.element.querySelector('em')
    if (pupil) Object.defineProperty(pupil, 'offsetWidth', { configurable: true, value: 7 })
  })
  wrapper.findAll('.monster').forEach(body => vi.spyOn(body.element, 'getBoundingClientRect').mockReturnValue(new DOMRect(100, 250, 100, 250)))
  return wrapper
}
beforeEach(() => {
  vi.clearAllMocks()
  motion.reduced = false
  motion.trackers = []
  motion.cleanups = []
  frames.clear()
  vi.stubGlobal('matchMedia', vi.fn((query: string) => ({ matches: query.includes(': reduce') && motion.reduced })))
  vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => { frames.set(++frameId, callback); return frameId })
  vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(id => { frames.delete(id) })
  api.captcha.mockResolvedValue({ data: { captchaKey: 'captcha-test-key', captchaImage: 'data:image/png;base64,TEST' } })
  api.login.mockResolvedValue(undefined)
})
afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount())
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('登录页互动与原有登录行为', () => {
  it('快速移动只处理同一帧中最新位置，瞳孔和身体都会回应', async () => {
    const wrapper = await render()
    await wrapper.trigger('pointermove', { clientX: 10, clientY: 310, pointerType: 'mouse' })
    await wrapper.trigger('pointermove', { clientX: 900, clientY: 310, pointerType: 'mouse' })
    expect(frames.size).toBe(1)
    const callback = [...frames.values()][0]!
    frames.clear()
    callback(16)
    const pupil = motion.trackers.find(item => item.element.tagName === 'EM' && item.property === 'x')!
    const body = motion.trackers.find(item => item.element.classList.contains('monster') && item.property === 'rotation')!
    expect(pupil.to).toHaveBeenCalledTimes(1)
    expect(pupil.to.mock.calls[0]![0]).toBeGreaterThan(0)
    expect(body.to).toHaveBeenCalledTimes(1)
  })
  it('离开后回位并取消未执行的移动，卸载也取消帧', async () => {
    const wrapper = await render()
    await wrapper.trigger('pointermove', { clientX: 900, clientY: 310, pointerType: 'mouse' })
    await wrapper.trigger('pointerleave')
    expect(frames.size).toBe(0)
    expect(motion.trackers.find(item => item.element.tagName === 'EM' && item.property === 'x')!.to).toHaveBeenLastCalledWith(0)
    await wrapper.trigger('pointermove', { clientX: 900, clientY: 310, pointerType: 'mouse' })
    wrapper.unmount()
    wrappers.splice(wrappers.indexOf(wrapper), 1)
    expect(frames.size).toBe(0)
  })
  it('触摸与减少动态效果模式不会触发跟随', async () => {
    const wrapper = await render()
    await wrapper.trigger('pointermove', { clientX: 900, clientY: 310, pointerType: 'touch' })
    expect(frames.size).toBe(0)
    motion.reduced = true
    await wrapper.trigger('pointermove', { clientX: 900, clientY: 310, pointerType: 'mouse' })
    expect(frames.size).toBe(0)
  })
  it('仍使用真实验证码 key 和完整密码提交，成功后进入问数页', async () => {
    const wrapper = await render()
    await wrapper.find('input[autocomplete="username"]').setValue(' reader ')
    await wrapper.find('input[autocomplete="current-password"]').setValue(' secret ')
    await wrapper.find('#login-captcha').setValue('K7M2')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(api.login).toHaveBeenCalledWith({ username: 'reader', password: ' secret ', captchaKey: 'captcha-test-key', captchaCode: 'K7M2' }, expect.any(Function))
    expect(api.login.mock.calls[0][1]()).toBe(true)
    wrapper.unmount()
    expect(api.login.mock.calls[0][1]()).toBe(false)
    expect(api.replace).toHaveBeenCalledWith('/query')
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
