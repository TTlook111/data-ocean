import type { IamS1QueryTaskResult } from '../api/iamS1'

export type QueryChartType = 'bar' | 'line' | 'pie'
type ChartObject = Record<string, unknown>
type ChartPoint = { name: string; value: number }

export interface QueryChartTheme {
  colors?: string[]
  textColor?: string
  mutedColor?: string
  borderColor?: string
  fontFamily?: string
}

function object(value: unknown): ChartObject {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as ChartObject : {}
}

function seriesOf(option: ChartObject): ChartObject[] {
  const value = Array.isArray(option.series) ? option.series : [option.series]
  return value.filter((item) => item !== null && typeof item === 'object').map(object)
}

function axisOf(option: ChartObject, key: 'xAxis' | 'yAxis', index: unknown): ChartObject {
  const axes = Array.isArray(option[key]) ? option[key] : [option[key]]
  return object(axes[typeof index === 'number' ? index : 0])
}

function numeric(value: unknown): number | null {
  if (typeof value !== 'number' && (typeof value !== 'string' || !/^[+-]?(?:\d+\.?\d*|\.\d+)(?:e[+-]?\d+)?$/i.test(value.trim()))) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

function category(value: unknown): string | null {
  const raw = typeof value === 'object' ? object(value).value : value
  return typeof raw === 'string' || typeof raw === 'number' ? String(raw) : null
}

function datasetPoints(option: ChartObject, series: ChartObject, horizontal: boolean): ChartPoint[] | null {
  const datasets = Array.isArray(option.dataset) ? option.dataset : [option.dataset]
  const dataset = object(datasets[typeof series.datasetIndex === 'number' ? series.datasetIndex : 0])
  if (!Array.isArray(dataset.source) || !dataset.source.length || series.seriesLayoutBy === 'row') return null
  const source = dataset.source as unknown[]
  const first = source[0]
  let dimensions = (Array.isArray(dataset.dimensions) ? dataset.dimensions : [])
    .map((dimension) => typeof dimension === 'string' ? dimension : object(dimension).name)
  const encode = object(series.encode)
  let rows = source
  if (Array.isArray(first)) {
    const header = dataset.sourceHeader === true || (dataset.sourceHeader !== false
      && first.every((item) => typeof item === 'string')
      && first.every((item) => numeric(item) === null))
    if (header) {
      if (!dimensions.length) dimensions = first
      rows = source.slice(1)
    }
  } else if (!dimensions.length) {
    dimensions = Object.keys(object(first))
  }
  const dimensionIndex = (value: unknown, fallback: number): number => {
    const scalar = Array.isArray(value) && value.length === 1 ? value[0] : value
    if (typeof scalar === 'number') return scalar
    if (typeof scalar === 'string') return dimensions.indexOf(scalar)
    return scalar === undefined && dimensions.length === 2 ? fallback : -1
  }
  const nameIndex = dimensionIndex(encode.itemName ?? encode[horizontal ? 'y' : 'x'], 0)
  const valueIndex = dimensionIndex(encode.value ?? encode[horizontal ? 'x' : 'y'], 1)
  if (nameIndex < 0 || valueIndex < 0 || nameIndex === valueIndex) return null
  const points: ChartPoint[] = []
  for (const row of rows) {
    const values = Array.isArray(row) ? row : dimensions.map((dimension) => object(row)[String(dimension)])
    const name = category(values[nameIndex])
    const value = numeric(values[valueIndex])
    if (name === null || value === null) return null
    points.push({ name, value })
  }
  return points.length ? points : null
}

/** Resolve only the categories and values already declared by the chart. */
function pointsOf(option: ChartObject): ChartPoint[] | null {
  const series = seriesOf(option)
  if (series.length !== 1) return null
  const current = series[0]!
  const x = axisOf(option, 'xAxis', current.xAxisIndex)
  const y = axisOf(option, 'yAxis', current.yAxisIndex)
  const horizontal = y.type === 'category' || (Array.isArray(y.data) && x.type !== 'category')
  const labels = horizontal ? y.data : x.data
  if (!Array.isArray(current.data)) return datasetPoints(option, current, horizontal)
  if (Array.isArray(labels) && labels.length !== current.data.length) return null
  const points: ChartPoint[] = []
  for (const [index, item] of current.data.entries()) {
    const entry = object(item)
    const raw = entry.value ?? item
    const name = Array.isArray(labels) ? category(labels[index]) : category(entry.name)
    const value = numeric(Array.isArray(raw) && raw.length === 2 ? raw[horizontal ? 0 : 1] : raw)
    if (name === null || value === null) return null
    points.push({ name, value })
  }
  return points.length ? points : null
}

export function getPieChartNote(config: ChartObject | undefined): string {
  if (!config) return ''
  if (seriesOf(config).length !== 1) return '多个指标请用柱状图或折线图比较。'
  const points = pointsOf(config)
  if (!points) return '当前图表缺少明确的类别与数值，无法转换为占比图。'
  if (points.some((point) => point.value < 0)) return '数据包含负值，不适合用饼图表达占比。'
  if (!points.some((point) => point.value > 0)) return '当前数值均为零，暂无可比较的占比。'
  if (points.length > 6) return '类别较多，使用柱状图或折线图更容易比较。'
  return ''
}

export function getQueryChartTypes(config: ChartObject | undefined): QueryChartType[] {
  if (!config) return []
  const series = seriesOf(config)
  if (!series.length || series.some((item) => !['bar', 'line', 'pie'].includes(String(item.type)))) return []
  if (series.some((item) => item.type === 'pie') && !pointsOf(config)) return ['pie']
  return getPieChartNote(config) ? ['bar', 'line'] : ['bar', 'line', 'pie']
}

export function getDefaultQueryChartType(config: ChartObject | undefined): QueryChartType {
  const native = config ? seriesOf(config)[0]?.type : undefined
  const types = getQueryChartTypes(config)
  return types.includes(native as QueryChartType) ? native as QueryChartType : types[0] || 'bar'
}

export function getQueryChartTitle(result: Pick<IamS1QueryTaskResult, 'chartConfig' | 'question'> | null): string {
  if (!result?.chartConfig) return '数据概览'
  const names = [...new Set(seriesOf(result.chartConfig).map((series) => series.name)
    .filter((name): name is string => typeof name === 'string' && !!name.trim()))]
  const short = (value: unknown): value is string => typeof value === 'string'
    && !!value.trim() && value.trim().length <= 36 && value.trim() !== result.question?.trim()
  if (names.length === 1 && short(names[0])) return names[0].trim()
  if (names.length > 1) return '指标对比'
  const title = Array.isArray(result.chartConfig.title) ? result.chartConfig.title[0] : result.chartConfig.title
  const text = typeof title === 'string' ? title : object(title).text
  return short(text) ? text.trim() : '数据概览'
}

/** Build a fresh option so a chart switch cannot retain the previous axes or alter server data. */
export function buildQueryChartOption(
  config: ChartObject | undefined,
  requestedType: QueryChartType,
  theme: QueryChartTheme = {},
): ChartObject | null {
  if (!config) return null
  let option: ChartObject
  try { option = JSON.parse(JSON.stringify(config)) as ChartObject } catch { return null }
  const series = seriesOf(option)
  const types = getQueryChartTypes(option)
  const type = types.includes(requestedType) ? requestedType : getDefaultQueryChartType(option)
  const titles = Array.isArray(option.title) ? option.title : [option.title]
  option.title = titles.map((title) => ({ ...object(title), show: false }))
  if (theme.colors?.length) option.color = theme.colors
  option.textStyle = { ...object(option.textStyle), ...(theme.fontFamily ? { fontFamily: theme.fontFamily } : {}), ...(theme.textColor ? { color: theme.textColor } : {}) }
  if (!types.length) return option

  if (type === 'pie') {
    const points = pointsOf(option)
    for (const key of ['xAxis', 'yAxis', 'grid', 'dataZoom', 'axisPointer']) delete option[key]
    if (points) delete option.dataset
    option.tooltip = { trigger: 'item', renderMode: 'richText', formatter: '{b}: {c} ({d}%)' }
    option.legend = { type: 'scroll', bottom: 0, left: 'center', icon: 'circle', itemWidth: 9, itemHeight: 9, textStyle: { color: theme.mutedColor, fontSize: 12 } }
    option.series = [{
      ...(points ? { name: series[0]?.name } : series[0]),
      type: 'pie', radius: ['36%', '64%'], center: ['50%', '44%'],
      ...(points ? { data: points } : {}),
      stillShowZeroSum: false, percentPrecision: 1, avoidLabelOverlap: true,
      itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
      label: { show: true, position: 'outside', formatter: '{b}\n{c} · {d}%', fontSize: 12, lineHeight: 19, color: theme.textColor, width: 100, overflow: 'truncate' },
      labelLine: { show: true, length: 12, length2: 10 },
      emphasis: { scale: true, scaleSize: 4 },
    }]
  } else {
    const points = series[0]?.type === 'pie' ? pointsOf(option) : null
    if (points) {
      delete option.dataset
      delete option.legend
      option.xAxis = { type: 'category', data: points.map((point) => point.name) }
      option.yAxis = { type: 'value' }
      option.series = [{ name: series[0]?.name, type, data: points.map((point) => point.value) }]
    } else {
      option.series = series.map((item) => {
        const data = Array.isArray(item.data) ? item.data : []
        const readableValues = data.length > 0 && data.length <= 8
          && data.every((entry) => numeric(object(entry).value ?? entry) !== null)
        return {
          ...item, type,
          label: { ...object(item.label), show: object(item.label).show ?? readableValues, position: 'top', fontSize: 12, color: theme.textColor },
          ...(type === 'bar'
            ? { barMaxWidth: 64, itemStyle: { ...object(item.itemStyle), borderRadius: [5, 5, 0, 0] } }
            : { symbol: 'circle', symbolSize: 7, lineStyle: { ...object(item.lineStyle), width: 2 } }),
        }
      })
    }
    const tooltip = { ...object(option.tooltip), trigger: 'axis', renderMode: 'richText', axisPointer: { type: type === 'bar' ? 'shadow' : 'line' } }
    delete (tooltip as ChartObject).formatter
    option.tooltip = tooltip
    if (!Array.isArray(option.grid)) option.grid = { ...object(option.grid), top: 24, right: 20, bottom: series.length > 1 ? 48 : 24, left: 12, containLabel: true }
    if (series.length > 1) option.legend = { ...object(option.legend), type: 'scroll', bottom: 0, left: 'center', textStyle: { color: theme.mutedColor, fontSize: 12 } }
    for (const key of ['xAxis', 'yAxis'] as const) {
      const axes = Array.isArray(option[key]) ? option[key] : [option[key]]
      option[key] = axes.map((axis) => ({ ...object(axis), axisLabel: { ...object(object(axis).axisLabel), color: theme.mutedColor, fontSize: 12, hideOverlap: true }, splitLine: { ...object(object(axis).splitLine), lineStyle: { ...object(object(object(axis).splitLine).lineStyle), color: theme.borderColor } } }))
    }
  }
  return option
}
