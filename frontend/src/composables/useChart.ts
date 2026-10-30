import { onBeforeUnmount, type Ref } from 'vue'
import * as echarts from 'echarts'

export function useChart(containerRef: Ref<HTMLDivElement | null>) {
  let instance: echarts.ECharts | null = null
  let resizeObserver: ResizeObserver | null = null

  function observeContainer() {
    const container = containerRef.value
    if (!container || resizeObserver || typeof ResizeObserver === 'undefined') return
    resizeObserver = new ResizeObserver(() => resize())
    resizeObserver.observe(container)
  }

  function init() {
    if (!containerRef.value) return null
    if (instance) return instance
    instance = echarts.init(containerRef.value)
    observeContainer()
    return instance
  }

  function setOption(option: Record<string, unknown>) {
    if (!instance && containerRef.value) {
      init()
    }
    if (instance) {
      instance.setOption(option, true)
      resize()
    }
  }

  function resize() {
    instance?.resize()
  }

  function dispose() {
    resizeObserver?.disconnect()
    resizeObserver = null
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
  if (typeof window !== 'undefined') window.addEventListener('resize', handleResize)

  onBeforeUnmount(() => {
    if (typeof window !== 'undefined') window.removeEventListener('resize', handleResize)
    dispose()
  })

  return { init, setOption, resize, dispose, getDataURL, getInstance }
}
