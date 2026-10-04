import { describe, expect, it } from 'vitest'
import { getLoginLookOffset } from './loginMotion'

describe('登录角色视线范围', () => {
  it('光标位于眼睛中心时不偏移，靠近左右角色时视线方向不同', () => {
    expect(getLoginLookOffset({ x: 200, y: 300 }, { x: 200, y: 300 }, 5)).toEqual({ x: 0, y: 0 })
    expect(getLoginLookOffset({ x: 200, y: 300 }, { x: 100, y: 300 }, 5).x).toBeGreaterThan(0)
    expect(getLoginLookOffset({ x: 200, y: 300 }, { x: 300, y: 300 }, 5).x).toBeLessThan(0)
  })
  it('光标在屏幕角落也不会让瞳孔越过眼眶', () => {
    for (const pointer of [{ x: -10000, y: -10000 }, { x: 10000, y: 10000 }, { x: 10, y: 20 }]) {
      const offset = getLoginLookOffset(pointer, { x: 200, y: 300 }, 4)
      expect(Math.hypot(offset.x, offset.y)).toBeLessThanOrEqual(4.000001)
    }
  })
})
