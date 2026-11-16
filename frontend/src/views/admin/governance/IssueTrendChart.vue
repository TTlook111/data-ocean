<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { IssueTrendDay } from '../../../api/admin/governance'
import { useChart } from '../../../composables/useChart'
import { readChartTheme } from '../../../utils/chartTheme'
import { issueDayFlows } from './issueTrend'

const props = defineProps<{ days: IssueTrendDay[]; selectedDate: string }>()
const emit = defineEmits<{ select: [date: string] }>()
const canvas = ref<HTMLDivElement | null>(null)
const { setOption, getInstance } = useChart(canvas)
const selected = computed(() => props.days.find(day => day.date === props.selectedDate))

function draw() {
  if (!canvas.value) return
  const { color, fontFamily } = readChartTheme(canvas.value)
  // 与图外图例共用局部看板色。余额、每日进入/移出为独立量，颜色也保留相同含义。
  const balance = color('--do-dash-cobalt')
  const entering = color('--do-dash-in')
  const leaving = color('--do-dash-out')
  const muted = color('--do-dash-muted')
  const surface = color('--do-dash-surface')
  const line = { color: color('--do-dash-grid'), width: 1 }
  const dates = props.days.map(day => day.date)
  // 边缘观测值朝画布内标注；中部峰值上方预留完整数字的高度。
  const pointLabelPosition = props.selectedDate === dates.at(-1) ? 'left'
    : props.selectedDate === dates[0] ? 'right' : 'top'
  const coveredAt = props.days.findIndex(day => day.coverage !== 'NONE')
  const flows = props.days.map(issueDayFlows)
  const categories = { type: 'category', data: dates, boundaryGap: true, axisTick: { show: false } }
  const axis = {
    type: 'value', minInterval: 1, splitNumber: 3, axisLabel: { color: muted, fontSize: 11 },
    splitLine: { lineStyle: line },
  }
  // 画布两层共用同一类目和左右留白；流量上下对称，用户可以直接比较同日的增减。
  const flowBound = Math.max(1, ...flows.map(flow => flow ? Math.max(flow.entered, flow.left) : 0))
  const tick = Math.max(1, Math.ceil(flowBound / 2))
  const guide = {
    silent: true, symbol: 'none', label: { show: false },
    lineStyle: { type: 'dashed', width: 1, color: color('--do-dash-target') },
    data: [{ xAxis: props.selectedDate }],
  }
  setOption({
    animation: false, textStyle: { fontFamily },
    grid: [{ left: 48, right: 22, top: 28, height: '42%' }, { left: 48, right: 22, top: '62%', bottom: 32 }],
    xAxis: [
      { ...categories, gridIndex: 0, axisLine: { show: false }, axisLabel: { show: false } },
      { ...categories, gridIndex: 1, axisLine: { lineStyle: line, onZero: false }, axisLabel: {
        color: muted, hideOverlap: true, showMinLabel: true, showMaxLabel: true,
        formatter: (date: string) => date.slice(5).replace('-', '/'),
      } },
    ],
    yAxis: [
      { ...axis, gridIndex: 0, min: 0 },
      { ...axis, gridIndex: 1, min: -tick * 2, max: tick * 2, interval: tick * 2,
        axisLabel: { color: muted, fontSize: 11, formatter: (n: number) => n > 0 ? `+${n}` : n } },
    ],
    series: [
      {
        name: '未解决问题', type: 'line', xAxisIndex: 0, yAxisIndex: 0, connectNulls: false,
        data: props.days.map(day => day.remaining), symbol: 'circle', showAllSymbol: true,
        symbolSize: props.days.length - coveredAt === 1 ? 10 : 5, itemStyle: { color: balance }, lineStyle: { width: 2.5, color: balance },
        areaStyle: { color: balance, opacity: .03 }, markLine: guide,
        // 记录刚启用时图上可能只有一个有效点；将前段画成无数据底色，不能连接成历史趋势。
        markArea: coveredAt > 1 ? {
          silent: true, itemStyle: { color: color('--do-dash-gap') },
          label: { show: true, position: 'inside', color: muted, formatter: '此前无历史记录' },
          data: [[{ xAxis: dates[0] }, { xAxis: dates[coveredAt - 1] }]],
        } : undefined,
        markPoint: {
          // 白心蓝圈只标出已观察的值，单独一个点和键盘选中的日期均清晰可辨。
          symbol: 'circle', symbolSize: 10, itemStyle: { color: surface, borderColor: balance, borderWidth: 3 },
          label: { position: pointLabelPosition, color: balance, fontWeight: 600, formatter: '{c}' },
          data: selected.value?.remaining != null
            ? [{ coord: [props.selectedDate, selected.value.remaining], value: selected.value.remaining }] : [],
        },
      },
      { name: '进入待处理', type: 'bar', stack: 'change', xAxisIndex: 1, yAxisIndex: 1,
        barMaxWidth: 28, itemStyle: { color: entering }, data: flows.map(flow => flow?.entered ?? null),
        markLine: guide },
      { name: '移出待处理', type: 'bar', stack: 'change', xAxisIndex: 1, yAxisIndex: 1,
        barMaxWidth: 28, itemStyle: { color: leaving }, data: flows.map(flow => flow ? -flow.left : null) },
    ],
  })
}

onMounted(() => {
  draw()
  const chart = getInstance()
  // 点击列内空白也能选中日期。无记录日只表示缺乏覆盖，仍可查看对应说明。
  chart?.getZr().on('click', event => {
    const position = [event.offsetX, event.offsetY]
    const gridIndex = chart.containPixel({ gridIndex: 0 }, position) ? 0 : 1
    if (!chart.containPixel({ gridIndex }, position)) return
    const point = chart.convertFromPixel({ gridIndex }, position)
    const day = props.days[Math.round(point?.[0] ?? -1)]
    if (day) emit('select', day.date)
  })
})
watch(() => [props.days, props.selectedDate], draw)
</script>

<template>
  <!-- 原生选择器在父视图中提供同一组日期和精确数字，读屏及键盘无需操作画布。 -->
  <div ref="canvas" class="issue-trend-chart" role="img" aria-label="未解决问题余额折线与同日进出待处理柱图" />
</template>

<style scoped>
.issue-trend-chart { width: 100%; height: clamp(275px, 36vh, 370px); min-width: 0; }
</style>
