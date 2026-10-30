import { describe, expect, it } from 'vitest'
import { buildQueryChartOption, getDefaultQueryChartType, getPieChartNote, getQueryChartTitle, getQueryChartTypes } from './queryChart'

const question = '按商品类别统计全部已完成订单的销售额，其中销售额为已完成订单的 quantity 与 unit_price 的乘积。'
const categoryChart = {
  title: { text: question },
  xAxis: { type: 'category', data: ['Furniture', 'Accessories'] },
  yAxis: { type: 'value' },
  grid: { top: 60 },
  dataZoom: [{ type: 'inside' }],
  tooltip: { trigger: 'axis' },
  series: [{ type: 'bar', name: '商品类别销售额', data: [180, 30] }],
}

describe('query chart presentation', () => {
  it('converts the reported regression into named pie slices with no Cartesian leftovers or duplicate canvas title', () => {
    const before = JSON.stringify(categoryChart)
    const option = buildQueryChartOption(categoryChart, 'pie')!
    expect(option).not.toHaveProperty('xAxis')
    expect(option).not.toHaveProperty('yAxis')
    expect(option).not.toHaveProperty('grid')
    expect(option).not.toHaveProperty('dataZoom')
    expect(option.title).toEqual([{ text: question, show: false }])
    expect(option.series).toMatchObject([{
      type: 'pie', data: [{ name: 'Furniture', value: 180 }, { name: 'Accessories', value: 30 }],
      label: { show: true, formatter: '{b}\n{c} · {d}%' },
    }])
    expect(option.tooltip).toMatchObject({ trigger: 'item', renderMode: 'richText' })
    expect(JSON.stringify(categoryChart)).toBe(before)
  })

  it('switches back to line and bar from the original data without carrying pie options', () => {
    buildQueryChartOption(categoryChart, 'pie')
    for (const type of ['line', 'bar'] as const) {
      const option = buildQueryChartOption(categoryChart, type)!
      expect(option.series).toMatchObject([{ type, name: '商品类别销售额', data: [180, 30] }])
      expect((option.series as Array<Record<string, unknown>>)[0]).not.toHaveProperty('radius')
      expect(option.xAxis).toMatchObject([{ type: 'category', data: ['Furniture', 'Accessories'] }])
      expect(option.tooltip).toMatchObject({ trigger: 'axis' })
    }
  })

  it('uses a short returned metric label instead of repeating the full question', () => {
    expect(getQueryChartTitle({ question, chartConfig: categoryChart })).toBe('商品类别销售额')
    expect(getQueryChartTitle({ question, chartConfig: { title: { text: question }, series: [{ type: 'bar' }] } })).toBe('数据概览')
  })

  it('preserves horizontal categories and decimal strings when converting to pie', () => {
    const option = buildQueryChartOption({
      xAxis: { type: 'value' }, yAxis: { type: 'category', data: ['北区', '南区'] },
      series: [{ type: 'bar', data: [{ value: '50.25' }, { value: '120.50' }] }],
    }, 'pie')!
    expect(option.series).toMatchObject([{ data: [{ name: '北区', value: 50.25 }, { name: '南区', value: 120.5 }] }])
  })

  it('supports named native pie data and safely converts it back to a Cartesian chart', () => {
    const source = { legend: { data: ['A', 'B'] }, series: [{ type: 'pie', name: '金额', data: [{ name: 'A', value: 5 }, { name: 'B', value: 7 }] }] }
    expect(getDefaultQueryChartType(source)).toBe('pie')
    const option = buildQueryChartOption(source, 'bar')!
    expect(option.xAxis).toMatchObject([{ data: ['A', 'B'] }])
    expect(option.series).toMatchObject([{ type: 'bar', data: [5, 7] }])
    expect(option).not.toHaveProperty('legend')
  })

  it('resolves dataset header and explicit encodings instead of losing the names', () => {
    const option = buildQueryChartOption({
      dataset: { source: [['product', 'amount'], ['桌子', 180], ['配件', 30]] },
      xAxis: { type: 'category' }, yAxis: { type: 'value' },
      series: [{ type: 'bar', encode: { x: 'product', y: 'amount' } }],
    }, 'pie')!
    expect(option.series).toMatchObject([{ data: [{ name: '桌子', value: 180 }, { name: '配件', value: 30 }] }])
    expect(option).not.toHaveProperty('dataset')
  })

  it('resolves object datasets with explicitly ordered dimensions', () => {
    const option = buildQueryChartOption({
      dataset: { dimensions: ['region', 'sales'], source: [{ sales: 2, region: 'East' }, { sales: 3, region: 'West' }] },
      xAxis: { type: 'category' }, series: [{ type: 'bar', encode: { x: 'region', y: 'sales' } }],
    }, 'pie')!
    expect(option.series).toMatchObject([{ data: [{ name: 'East', value: 2 }, { name: 'West', value: 3 }] }])
  })

  it('retains every metric when switching a multi-series chart and disables a misleading pie', () => {
    const chart = { ...categoryChart, series: [{ type: 'bar', name: '收入', data: [4, 7] }, { type: 'bar', name: '成本', data: [2, 3] }] }
    expect(getQueryChartTypes(chart)).toEqual(['bar', 'line'])
    expect(getPieChartNote(chart)).toContain('多个指标')
    expect(buildQueryChartOption(chart, 'line')?.series).toMatchObject([
      { name: '收入', type: 'line', data: [4, 7] }, { name: '成本', type: 'line', data: [2, 3] },
    ])
    expect(buildQueryChartOption(chart, 'pie')?.series).toMatchObject([{ type: 'bar' }, { type: 'bar' }])
  })

  it.each([[-2, 4], [0, 0], [null, 2], ['', 2]])('does not fabricate proportions for invalid pie values %j', (...values) => {
    const chart = { ...categoryChart, series: [{ type: 'bar', data: values }] }
    expect(getQueryChartTypes(chart)).not.toContain('pie')
    expect(getPieChartNote(chart)).not.toBe('')
  })

  it('rejects mismatched labels and multi-value encodings rather than picking an arbitrary metric', () => {
    const chart = { ...categoryChart, xAxis: { data: ['Only one label'] } }
    expect(getQueryChartTypes(chart)).not.toContain('pie')
    const dataset = { dataset: { dimensions: ['name', 'sales', 'cost'], source: [['A', 2, 1]] }, xAxis: { type: 'category' }, series: [{ type: 'bar', encode: { x: 'name', y: ['sales', 'cost'] } }] }
    expect(getQueryChartTypes(dataset)).not.toContain('pie')
  })

  it('retains multiple grids and axes while switching compatible series', () => {
    const chart = { ...categoryChart, grid: [{ top: 0 }, { top: '50%' }], xAxis: [{ type: 'category', data: ['A'] }, { type: 'category', data: ['B'], gridIndex: 1 }], series: [{ type: 'bar', data: [2] }, { type: 'bar', xAxisIndex: 1, data: [3] }] }
    const option = buildQueryChartOption(chart, 'line')!
    expect(option.grid).toEqual(chart.grid)
    expect(option.xAxis).toMatchObject([{ data: ['A'] }, { data: ['B'], gridIndex: 1 }])
  })

  it('does not create a chart when Java has withheld chartConfig', () => {
    expect(buildQueryChartOption(undefined, 'pie')).toBeNull()
    expect(getQueryChartTypes(undefined)).toEqual([])
  })
})
