// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import * as echarts from 'echarts'
import WaitingTimeChart from './WaitingTimeChart.vue'
import { waitLabel } from '../../../utils/workbenchPresentation'
import { readFileSync } from 'node:fs'
import { URL as NodeURL } from 'node:url'

const chart = vi.hoisted(() => ({
  setOption: vi.fn(), on: vi.fn(), containPixel: vi.fn(() => true), convertFromPixel: vi.fn(() => 1),
}))
vi.mock('../../../composables/useChart', () => ({ useChart: () => ({
  setOption: chart.setOption, getInstance: () => ({ ...chart, getZr: () => ({ on: chart.on }) }),
}) }))

// 测量不足时保留空桶。点图中的时段与键盘选择复用同一个服务端起点。
describe('平均等候时间趋势', () => {
  it('阈值相等保持正常，缺测不连线，点选返回精确桶', () => {
    const starts = ['2026-11-07T09:00:00','2026-11-07T09:30:00','2026-11-07T10:00:00']
    const samples = [10, null, 10.01]
    // happy-dom 不向子元素继承自定义属性，给绘图节点直接应用正式色板；跨组件继承另在浏览器验收。
    const style = document.createElement('style')
    style.textContent = readFileSync(new NodeURL('../../../styles/dashboard-palette.css', import.meta.url), 'utf8')
    const host = document.createElement('div')
    document.head.append(style)
    document.body.append(host)
    const wrapper = mount(WaitingTimeChart, { attachTo: host, attrs: { class: 'experience-home' }, props: {
      period:'today', target:10, selected:starts[2], buckets:starts.map((start,i) => ({
        start, end:starts[i+1] ?? '2026-11-07T10:30:00', measured:samples[i] == null ? 0 : 4, slow:i===2?1:0, avgSeconds:samples[i]!,
      })),
    } })
    try {
      const option = chart.setOption.mock.calls.at(-1)![0]
      // 等于目标的点沿用圆形，超过目标的实测点才画菱形；中间没有观测值时仍然断线。
      expect(option.series[0]).toMatchObject({
        symbol: 'circle', connectNulls: false, lineStyle: { color: '#2854cc' },
        data: [10, null, { value: 10.01, symbol: 'diamond', itemStyle: { color: '#b1374c' } }],
      })
      expect(option.series[0].markPoint.data[0].value).toBe('>10.0')
      // 用实际 SVG 渲染器检查带空桶和选中标记的组合，避免只验证配置而图形留白。
      const svgChart = echarts.init(null, null, { renderer: 'svg', ssr: true, width: 600, height: 200 })
      try {
        svgChart.setOption(option)
        expect(svgChart.renderToSVGString()).toContain('<path')
      } finally { svgChart.dispose() }
      expect(waitLabel(10,10)).toBe('10.0')
      chart.on.mock.calls[0]![1]({offsetX:100,offsetY:20})
      expect(wrapper.emitted('select')).toEqual([[starts[1]]])
    } finally { wrapper.unmount(); host.remove(); style.remove() }
  })
})
