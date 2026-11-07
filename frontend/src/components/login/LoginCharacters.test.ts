// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import LoginCharacters from './LoginCharacters.vue'

let wrapper: VueWrapper
let activeMotion: boolean
let hasHover: boolean
let media: EventTarget
let sequence: number
const frames = new Map<number, FrameRequestCallback>()

// 用可控帧和稳定的脸部几何验证实际位移与取消时机。
beforeEach(() => {
  activeMotion = true
  hasHover = true
  sequence = 0
  frames.clear()
  media = new EventTarget()
  Object.defineProperty(media, 'matches', { get: () => activeMotion })
  // 在触屏场景单独关闭悬停能力，让主动轻点仍能遵循系统的动态偏好。
  const hover = new EventTarget()
  Object.defineProperty(hover, 'matches', { get: () => hasHover })
  vi.spyOn(window, 'matchMedia').mockImplementation(query =>
    (query.includes('any-hover') ? hover : media) as MediaQueryList,
  )
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(new DOMRect(20, 30, 80, 40))
  vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => {
    frames.set(++sequence, callback)
    return sequence
  })
  vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(id => { frames.delete(id) })
  wrapper = mount(LoginCharacters, { props: { conceal: false }, attachTo: document.body })
})

afterEach(() => {
  wrapper.unmount()
  vi.restoreAllMocks()
})

function move(x: number, y: number, pointerType = 'mouse') {
  window.dispatchEvent(new PointerEvent('pointermove', { pointerType, clientX: x, clientY: y }))
}
function paintFrame() {
  const pending = [...frames.values()]
  frames.clear()
  pending.forEach(callback => callback(16))
}
function gaze(axis: 'x' | 'y') {
  return (wrapper.get('.character--tall').element as HTMLElement).style.getPropertyValue(`--look-${axis}`)
}

// 走实际角色元素的 DOM 事件，给 happy-dom 缺少的 Web Animations 接口装上可观察的回执。
function interactiveFigure() {
  const el = wrapper.get('.character--arch').element as HTMLElement
  const cancel = vi.fn()
  const animate = vi.fn(() => ({ cancel }))
  Object.defineProperty(el, 'animate', { value: animate })
  const down = () => el.dispatchEvent(new PointerEvent('pointerdown', {
    bubbles: true, cancelable: true, isPrimary: true, pointerType: 'touch', pointerId: 9, clientX: 20, clientY: 20,
  }))
  return { el, animate, cancel, down }
}

describe('登录角色', () => {
  it('同帧多次移动只处理最后位置，视线有明确方向且保持在眼眶内', () => {
    move(-500, -500)
    move(850, 420)
    expect(frames.size).toBe(1)
    paintFrame()
    expect(Number(gaze('x'))).toBeGreaterThan(0)
    expect(Number(gaze('y'))).toBeGreaterThan(0)
    expect(Math.hypot(Number(gaze('x')), Number(gaze('y')))).toBeLessThan(1.001)
    document.documentElement.dispatchEvent(new Event('pointerleave'))
    expect(gaze('x')).toBe('')
  })

  it('输入密码时取消在途帧并合眼，结束后由下一次鼠标移动恢复', async () => {
    move(700, 60)
    paintFrame()
    move(800, 30)
    await wrapper.setProps({ conceal: true })
    expect(wrapper.classes()).toContain('is-concealed')
    expect(gaze('x')).toBe('')
    expect(frames.size).toBe(0)
    move(900, 90)
    expect(frames.size).toBe(0)
    await wrapper.setProps({ conceal: false })
    expect(frames.size).toBe(0)
    move(-150, 10)
    paintFrame()
    expect(Number(gaze('x'))).toBeLessThan(0)
  })

  it('动态偏好变更及触屏均停止跟随，卸载后不再安排帧', () => {
    move(600, 60)
    paintFrame()
    activeMotion = false
    media.dispatchEvent(new Event('change'))
    expect(gaze('x')).toBe('')
    move(850, 90)
    expect(frames.size).toBe(0)
    activeMotion = true
    media.dispatchEvent(new Event('change'))
    move(500, 40)
    move(500, 40, 'touch')
    expect(frames.size).toBe(0)
    move(500, 40)
    wrapper.unmount()
    expect(frames.size).toBe(0)
    move(600, 50)
    expect(frames.size).toBe(0)
  })

  // 此前停在别处的鼠标方向，不能遮住这次键盘进入表单后的视线。
  it('关注表单时取消旧指针位置，闭眼优先于关注', async () => {
    move(-500, -500)
    paintFrame()
    await wrapper.setProps({ attentive: true })
    expect(wrapper.classes()).toContain('is-attentive')
    expect(gaze('x')).toBe('')
    move(800, 50)
    paintFrame()
    expect(Number(gaze('x'))).toBeGreaterThan(0)
    await wrapper.setProps({ conceal: true })
    expect(wrapper.classes()).not.toContain('is-attentive')
    expect(gaze('x')).toBe('')
  })

  it('无悬停设备也能轻点同一角色重播，取消和拖动不触发', () => {
    hasHover = false
    const { el, animate, cancel, down } = interactiveFigure()
    expect(down()).toBe(true)
    // 真机在生成兼容 click 前可以先报告触点离开文档。
    document.documentElement.dispatchEvent(new PointerEvent('pointerleave', { pointerType: 'touch' }))
    el.click()
    down()
    el.click()
    expect(animate).toHaveBeenCalledTimes(2)
    expect(cancel).toHaveBeenCalledTimes(1)
    down()
    window.dispatchEvent(new PointerEvent('pointermove', { pointerId: 9, pointerType: 'touch', clientX: 20, clientY: 40 }))
    el.click()
    down()
    window.dispatchEvent(new PointerEvent('pointercancel', { pointerId: 9 }))
    el.click()
    expect(animate).toHaveBeenCalledTimes(2)
  })

  it('密码与减少动态偏好撤销在途问候并阻止重播，鼠标按下保留输入焦点', async () => {
    const { el, animate, cancel, down } = interactiveFigure()
    expect(el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }))).toBe(false)
    down()
    el.click()
    await wrapper.setProps({ conceal: true })
    expect(cancel).toHaveBeenCalledOnce()
    down()
    el.click()
    await wrapper.setProps({ conceal: false })
    down()
    activeMotion = false
    media.dispatchEvent(new Event('change'))
    el.click()
    down()
    el.click()
    expect(animate).toHaveBeenCalledOnce()
  })
})
