// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import LoginCharacters from './LoginCharacters.vue'

let wrapper: VueWrapper
let activeMotion: boolean
let media: EventTarget
let sequence: number
const frames = new Map<number, FrameRequestCallback>()

// 用可控帧和稳定的脸部几何验证实际位移与取消时机。
beforeEach(() => {
  activeMotion = true
  sequence = 0
  frames.clear()
  media = new EventTarget()
  Object.defineProperty(media, 'matches', { get: () => activeMotion })
  vi.spyOn(window, 'matchMedia').mockReturnValue(media as MediaQueryList)
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(new DOMRect(20, 30, 80, 40))
  vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => {
    frames.set(++sequence, callback)
    return sequence
  })
  vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(id => { frames.delete(id) })
  wrapper = mount(LoginCharacters, { props: { conceal: false } })
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
})
