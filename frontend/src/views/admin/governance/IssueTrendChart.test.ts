// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import * as echarts from 'echarts'
import IssueTrendChart from './IssueTrendChart.vue'

const capture = vi.hoisted(() => ({ setOption: vi.fn(), getInstance: () => null }))
vi.mock('../../../composables/useChart', () => ({ useChart: () => capture }))

// 两端的大数和中部最高值都用真正的矢量渲染器量取字形边界；此处的数字仅用于布局回归。
describe('问题趋势标签', () => {
  // 迁移首日之前没有状态流水，白心选择圈只能落在服务端确认过的读数（包括零）上。
  it('选中无记录日时不绘点，选中实际零值时保留点与数值', async () => {
    const days = [
      { date: '2026-11-06', coverage: 'NONE' as const, remaining: null, added: null, reopened: null, resolved: null, otherClosed: null },
      { date: '2026-11-07', coverage: 'PARTIAL' as const, remaining: 0, added: 0, reopened: 0, resolved: 0, otherClosed: 0 },
    ]
    const wrapper = mount(IssueTrendChart, { props: { days, selectedDate: days[0]!.date } })
    try {
      const latest = () => capture.setOption.mock.lastCall![0].series[0]
      expect(latest().data).toEqual([null, 0])
      expect(latest().markPoint.data).toEqual([])
      await wrapper.setProps({ selectedDate: days[1]!.date })
      expect(latest().markPoint.data).toEqual([{ coord: [days[1]!.date, 0], value: 0 }])
    } finally { wrapper.unmount() }
  })
  it('窄图中最左、最高和最右的点值均完整落在画布内', async () => {
    const days = [7319, 8000, 2946].map((remaining, i) => ({
      date: '2026-11-0' + (i + 1), remaining, coverage: 'FULL' as const, added: 1, reopened: 0, resolved: 1, otherClosed: 0,
    }))
    const wrapper = mount(IssueTrendChart, { props: { days, selectedDate: days[0]!.date } })
    const chart = echarts.init(null, null, { renderer: 'svg', ssr: true, width: 228, height: 275 })
    try {
      for (const day of days) {
        await wrapper.setProps({ selectedDate: day.date })
        chart.setOption(capture.setOption.mock.lastCall![0], true)
        chart.renderToSVGString()
        const mark = chart.getZr().storage.getDisplayList(true).find(item => {
          const style = item.style as { text?: string; font?: string }
          return item.type === 'tspan' && String(style.text).replaceAll(',','') === String(day.remaining) &&
            (style.font ?? '').includes('600')
        })
        expect(mark, '选中日期的点值应被绘制').toBeDefined()
        const box = mark!.getBoundingRect().clone()
        const matrix = mark!.getComputedTransform()
        if (matrix) box.applyTransform(matrix)
        expect(box.x).toBeGreaterThanOrEqual(0)
        expect(box.y).toBeGreaterThanOrEqual(0)
        expect(box.x + box.width).toBeLessThanOrEqual(228)
        expect(box.y + box.height).toBeLessThanOrEqual(275)
      }
    } finally { chart.dispose(); wrapper.unmount() }
  })
})
