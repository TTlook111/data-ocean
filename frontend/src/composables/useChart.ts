import { onBeforeUnmount, type Ref } from 'vue'
import * as echarts from 'echarts'

export function useChart(containerRef: Ref<HTMLDivElement | null>) {
  let instance: echarts.ECharts | null = null

  function init() {
    if (!containerRef.value) return null
    if (instance) {
      instance.dispose()
    }
    instance = echarts.init(containerRef.value)
    return instance
  }

  function setOption(option: Record<string, unknown>) {
    if (!instance && containerRef.value) {
      init()
    }
    if (instance) {
      instance.setOption(option, true)
    }
  }

  function resize() {
    instance?.resize()
  }

  function dispose() {
    instance?.dispose()
    instance = null
  }

  function getDataURL(): string | null {
    if (!instance) return null
    return instance.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' })
  }

  function getInstance(): echarts.ECharts | null {
    return instance
  }

  const handleResize = () => resize()
  window.addEventListener('resize', handleResize)

  onBeforeUnmount(() => {
    window.removeEventListener('resize', handleResize)
    dispose()
  })

  return { init, setOption, resize, dispose, getDataURL, getInstance }
}
