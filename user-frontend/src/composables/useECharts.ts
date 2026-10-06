import { ref, onMounted, onUnmounted, type Ref } from 'vue'
import * as echarts from 'echarts'

export function useECharts(containerRef: Ref<HTMLElement | null>) {
  const chart = ref<echarts.ECharts | null>(null)

  function initChart() {
    if (!containerRef.value) return
    chart.value = echarts.init(containerRef.value)
  }

  function setOption(option: echarts.EChartsCoreOption) {
    if (chart.value) {
      chart.value.setOption(option, true)
    }
  }

  function resize() {
    chart.value?.resize()
  }

  onMounted(() => {
    initChart()
    window.addEventListener('resize', resize)
  })

  onUnmounted(() => {
    window.removeEventListener('resize', resize)
    chart.value?.dispose()
  })

  return { chart, setOption, resize }
}
