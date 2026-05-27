<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useChart } from '../../composables/useChart'

const props = defineProps<{
  option: Record<string, unknown> | null
}>()

const emit = defineEmits<{
  error: [msg: string]
}>()

const chartRef = ref<HTMLDivElement | null>(null)
const { setOption } = useChart(chartRef)

function renderChart() {
  if (!props.option) return
  try {
    setOption(props.option)
  } catch (e) {
    emit('error', '图表渲染失败')
  }
}

onMounted(renderChart)
watch(() => props.option, renderChart)
</script>

<template>
  <div ref="chartRef" class="chart-container"></div>
</template>

<style scoped>
.chart-container {
  width: 100%;
  height: 360px;
  min-height: 280px;
}
</style>
