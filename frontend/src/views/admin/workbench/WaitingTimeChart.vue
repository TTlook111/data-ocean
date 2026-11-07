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

// 同一条时间轴包含无样本的真实时段。断点保留，曲线只在超过工作台目标的部分标红。
function render() {
  if (!graph.value) return
  const { color } = readChartTheme(graph.value)
  const mint = color('--do-wait-mint'), coral = color('--do-wait-coral'), gridColor = color('--do-wait-line'), text = color('--do-wait-muted')
  const selectedIndex = props.buckets.findIndex(b => b.start === props.selected)
  const selectedPoint = props.buckets[selectedIndex]
  // ECharts 的折线渐变需要有限端点。下界为零，上界同时容纳目标线、实际峰值及点位标签。
  const axisMax = Math.ceil(Math.max(props.target * 1.3, ...props.buckets.map(b => (b.avgSeconds ?? 0) * 1.1)))
  setOption({
    animation: false,
    grid: { left: 5, right: 18, top: 20, bottom: 2, containLabel: true },
    xAxis: { type: 'category', data: labels.value, boundaryGap: false, axisTick: { show: false }, axisLine: { lineStyle: { color: gridColor } }, axisLabel: { color: text, hideOverlap: true } },
    yAxis: { type: 'value', min: 0, max: axisMax, splitNumber: 4, axisLabel: { color: text }, splitLine: { lineStyle: { color: gridColor } } },
    visualMap: { type: 'piecewise', dimension: 1, show: false, pieces: [{ gte: 0, lte: props.target, color: mint }, { gt: props.target, lte: axisMax, color: coral }] },
    series: [{
      type: 'line', data: props.buckets.map(b => b.avgSeconds), connectNulls: false, symbol: 'circle', symbolSize: 6,
      lineStyle: { width: 3 }, areaStyle: { color: mint, opacity: 0.055 },
      markLine: { silent: true, symbol: 'none', label: { show: false }, lineStyle: { color: text, type: 'dashed' }, data: [{ yAxis: props.target }] },
      markPoint: { silent: true, symbol: 'circle', symbolSize: 9, data: selectedPoint?.avgSeconds != null ? [{
        coord: [selectedIndex, selectedPoint.avgSeconds], value: waitLabel(selectedPoint.avgSeconds, props.target),
        itemStyle: { color: selectedPoint.avgSeconds > props.target ? coral : mint }, label: { show: true, position: 'top', color: text, formatter: '{c} 秒' },
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
