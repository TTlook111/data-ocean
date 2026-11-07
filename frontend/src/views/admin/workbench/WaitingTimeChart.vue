<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useChart } from '../../../composables/useChart'
import { readChartTheme } from '../../../utils/chartTheme'
import { bucketLabel, timeLabel, waitLabel } from '../../../utils/workbenchPresentation'
import type { ExperienceBucket, ExperiencePeriod } from '../../../api/admin/dashboard'

const props = defineProps<{ buckets: ExperienceBucket[]; period: ExperiencePeriod; target: number; selected?: string }>()
const emit = defineEmits<{ select: [start: string] }>()
const graph = ref<HTMLDivElement | null>(null)
const { setOption, getInstance } = useChart(graph)
const labels = computed(() => props.buckets.map(b => props.period === 'week' ? b.start.slice(5, 10) : timeLabel(b.start)))

// 曲线始终表示同一项等候指标。真实时段均值严格超目标时以红菱形标出，空桶留空。
function render() {
  if (!graph.value) return
  const { color } = readChartTheme(graph.value)
  const cobalt = color('--do-dash-cobalt'), alert = color('--do-dash-alert'), surface = color('--do-dash-surface')
  const gridColor = color('--do-dash-grid'), text = color('--do-dash-muted'), target = color('--do-dash-target')
  const observations = props.buckets.map(({ avgSeconds }) => {
    if (avgSeconds == null) return null
    return avgSeconds > props.target
      ? { value: avgSeconds, symbol: 'diamond', symbolSize: 10, itemStyle: { color: alert, borderColor: surface, borderWidth: 1 } }
      : avgSeconds
  })
  const selectedIndex = props.buckets.findIndex(b => b.start === props.selected)
  const selectedPoint = props.buckets[selectedIndex]
  // 从零计秒，上界同时容纳目标线、实际峰值及点位标签。
  const axisMax = Math.ceil(Math.max(props.target * 1.3, ...props.buckets.map(b => (b.avgSeconds ?? 0) * 1.1)))
  setOption({
    animation: false,
    grid: { left: 5, right: 18, top: 20, bottom: 2, containLabel: true },
    xAxis: { type: 'category', data: labels.value, boundaryGap: false, axisTick: { show: false }, axisLine: { lineStyle: { color: gridColor } }, axisLabel: { color: text, hideOverlap: true } },
    yAxis: { type: 'value', min: 0, max: axisMax, splitNumber: 4, axisLabel: { color: text }, splitLine: { lineStyle: { color: gridColor } } },
    series: [{
      type: 'line', data: observations, connectNulls: false, symbol: 'circle', symbolSize: 6, showAllSymbol: true,
      itemStyle: { color: surface, borderColor: cobalt, borderWidth: 2 },
      lineStyle: { width: 2.8, color: cobalt }, areaStyle: { color: cobalt, opacity: .035 },
      markLine: { silent: true, symbol: 'none', label: { show: false }, lineStyle: { color: target, type: 'dashed' }, data: [{ yAxis: props.target }] },
      // 选中标签随排查时段移动，仍沿用该点的圆/菱形与颜色。
      markPoint: { silent: true, data: selectedPoint?.avgSeconds != null ? [{
        coord: [selectedIndex, selectedPoint.avgSeconds], value: waitLabel(selectedPoint.avgSeconds, props.target),
        symbol: selectedPoint.avgSeconds > props.target ? 'diamond' : 'circle', symbolSize: 12,
        itemStyle: { color: surface, borderColor: selectedPoint.avgSeconds > props.target ? alert : cobalt, borderWidth: 2.5 },
        label: { show: true, position: 'top', color: color('--do-dash-ink'), fontWeight: 600, formatter: '{c} 秒' },
      }] : [] },
    }],
  })
}

// 图内指针和图下原生选择器指向同一个服务端桶，点击空桶同样能明确观察其无样本状态。
onMounted(() => {
  render()
  const chart = getInstance()
  chart?.getZr().on('click', event => {
    const pixel = [event.offsetX, event.offsetY]
    if (!chart.containPixel({ gridIndex: 0 }, pixel)) return
    const index = chart.convertFromPixel({ xAxisIndex: 0 }, event.offsetX) as number
    const point = props.buckets[Math.round(index)]
    if (point) emit('select', point.start)
  })
})
watch(() => [props.buckets, props.selected, props.target, props.period], render)
const description = computed(() => {
  const selected = props.buckets.find(b => b.start === props.selected)
  return selected?.avgSeconds != null ? `${bucketLabel(selected, props.period)}，平均等候 ${waitLabel(selected.avgSeconds, props.target)} 秒` : '所选时段无可计时回答'
})
</script>

<template>
  <div ref="graph" class="waiting-chart" role="img" :aria-label="`分时平均等候时间，目标 ${target} 秒。${description}。`" />
</template>

<style scoped>
/* 给绘图保留稳定高度，选中标签和等待数值变化时不挤动下方排查入口。 */
.waiting-chart { width: 100%; min-width: 0; height: clamp(164px, 23vh, 224px); }
</style>
