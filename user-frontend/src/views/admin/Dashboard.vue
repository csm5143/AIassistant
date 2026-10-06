<template>
  <div class="dashboard">
    <h1 class="page-title">仪表盘</h1>

    <!-- Stat Cards -->
    <div class="stat-cards">
      <div class="stat-card"><div class="stat-icon" style="background:rgba(224,224,224,0.12);color:#e0e0e0"><el-icon :size="22"><ChatDotRound /></el-icon></div><div class="stat-info"><div class="stat-value">{{ stats?.todaySessions ?? '-' }}</div><div class="stat-label">今日会话</div></div></div>
      <div class="stat-card"><div class="stat-icon" style="background:rgba(160,160,160,0.1);color: #b0b0b0"><el-icon :size="22"><ChatLineSquare /></el-icon></div><div class="stat-info"><div class="stat-value">{{ stats?.todayChats ?? '-' }}</div><div class="stat-label">今日对话</div></div></div>
      <div class="stat-card"><div class="stat-icon" style="background:rgba(160,160,160,0.1);color: #b0b0b0"><el-icon :size="22"><Promotion /></el-icon></div><div class="stat-info"><div class="stat-value">{{ stats?.todayToolCalls ?? '-' }}</div><div class="stat-label">工具调用</div></div></div>
      <div class="stat-card"><div class="stat-icon" style="background:rgba(224,224,224,0.1);color:#e0e0e0"><el-icon :size="22"><User /></el-icon></div><div class="stat-info"><div class="stat-value">{{ stats?.todayActiveUsers ?? '-' }}</div><div class="stat-label">活跃用户</div></div></div>
      <div class="stat-card"><div class="stat-icon" style="background:rgba(96,96,96,0.1);color: #b0b0b0"><el-icon :size="22"><Warning /></el-icon></div><div class="stat-info"><div class="stat-value">{{ stats?.todayGuardHits ?? '-' }}</div><div class="stat-label">安全拦截</div></div></div>
    </div>

    <!-- Trend charts row -->
    <el-row :gutter="16" style="margin-top:20px">
      <el-col :span="12">
        <div class="chart-card">
          <h3>对话 & 工具调用趋势（近7天）</h3>
          <div ref="trendChartRef" class="chart-box"></div>
        </div>
      </el-col>
      <el-col :span="12">
        <div class="chart-card">
          <h3>Token 使用趋势（近7天）</h3>
          <div ref="tokenChartRef" class="chart-box"></div>
        </div>
      </el-col>
    </el-row>

    <!-- Distribution + Logs row -->
    <el-row :gutter="16" style="margin-top:20px">
      <el-col :span="8">
        <div class="chart-card">
          <h3>API 调用分布</h3>
          <div ref="apiTypeChartRef" class="chart-box"></div>
        </div>
      </el-col>
      <el-col :span="8">
        <div class="chart-card">
          <h3>工具调用分布</h3>
          <div ref="toolChartRef" class="chart-box"></div>
        </div>
      </el-col>
      <el-col :span="8">
        <div class="chart-card">
          <h3>最新对话</h3>
          <div class="recent-list">
            <div v-for="chat in stats?.recentChats" :key="chat.id" class="recent-item">
              <span class="recent-user">{{ chat.userName }}</span>
              <span class="recent-text">{{ chat.question }}</span>
              <span class="recent-time">{{ formatTime(chat.createdAt) }}</span>
            </div>
            <div v-if="!stats?.recentChats?.length" class="recent-empty">暂无数据</div>
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { getDashboardStats, type DashboardStats } from '@/api'
import { ChatDotRound, ChatLineSquare, User, Warning, Promotion } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { useTheme } from '@/composables/useTheme'

const stats = ref<Record<string, any> | null>(null)
const trendChartRef = ref<HTMLElement | null>(null)
const tokenChartRef = ref<HTMLElement | null>(null)
const apiTypeChartRef = ref<HTMLElement | null>(null)
const toolChartRef = ref<HTMLElement | null>(null)

const { theme } = useTheme()
const light = computed(() => theme.value === 'light')
const chartPalette = computed(() => light.value ? ['#353e4b', '#626f80', '#8793a3', '#a5afbd', '#c3cbd5'] : ['#f5f5f5', '#c0c0c0', '#909090', '#666666', '#444444'])
const chartAxis = computed(() => ({
  axisLabel: { color: light.value ? '#5d6570' : '#b8b8b8' },
  axisLine: { lineStyle: { color: light.value ? '#b9c0ca' : '#686868' } },
  splitLine: { lineStyle: { color: light.value ? '#e2e5e9' : '#303030' } },
}))
const chartTheme = computed(() => ({
  color: chartPalette.value,
  backgroundColor: 'transparent',
  textStyle: { color: light.value ? '#20242b' : '#e0e0e0' },
  legend: { textStyle: { color: light.value ? '#5d6570' : '#cccccc' } },
  tooltip: { backgroundColor: light.value ? '#ffffff' : '#171717', borderColor: light.value ? '#d6dbe1' : '#686868', textStyle: { color: light.value ? '#20242b' : '#f5f5f5' } },
  categoryAxis: chartAxis.value,
  valueAxis: chartAxis.value,
  pie: { label: { color: light.value ? '#5d6570' : '#cccccc' }, itemStyle: { borderColor: light.value ? '#ffffff' : '#111111', borderWidth: 2 } },
}))
let chartTimer: ReturnType<typeof setTimeout> | undefined
function disposeCharts() { for (const element of [trendChartRef.value, tokenChartRef.value, apiTypeChartRef.value, toolChartRef.value]) if (element) echarts.getInstanceByDom(element)?.dispose() }
function renderCharts() { disposeCharts(); initTrendChart(); initTokenChart(); initApiTypeChart(); initToolChart() }
watch(theme, renderCharts)
onUnmounted(() => { clearTimeout(chartTimer); disposeCharts() })

function formatTime(s: string) { if (!s) return ''; const d = new Date(s); return d.toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'}) }

onMounted(async () => {
  try { const { data } = await getDashboardStats(); stats.value = data.data } catch {}
  chartTimer = setTimeout(renderCharts, 300)
})

function initTrendChart() {
  if (!trendChartRef.value || !stats.value) return
  const chart = echarts.init(trendChartRef.value, chartTheme.value)
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['对话','工具调用'], bottom: 0 },
    xAxis: { data: stats.value.trendLabels, axisLabel: { fontSize: 11 } },
    yAxis: { minInterval: 1 },
    series: [
      { name: '对话', type: 'line', data: stats.value.chatTrend, smooth: true, itemStyle: { color: chartPalette.value[0] } },
      { name: '工具调用', type: 'line', data: stats.value.toolTrend, smooth: true, symbol: 'diamond', lineStyle: { type: 'dashed' }, itemStyle: { color: '#909090' } },
    ],
    grid: { left: 40, right: 20, top: 20, bottom: 30 },
  })
}

function initTokenChart() {
  if (!tokenChartRef.value || !stats.value) return
  const chart = echarts.init(tokenChartRef.value, chartTheme.value)
  chart.setOption({
    tooltip: { trigger: 'axis' },
    xAxis: { data: stats.value.trendLabels, axisLabel: { fontSize: 11 } },
    yAxis: { minInterval: 1, axisLabel: { formatter: (v:number) => v>=1000?(v/1000).toFixed(0)+'K':v } },
    series: [
      { name: 'Token', type: 'bar', data: stats.value.tokenTrend, itemStyle: { color: chartPalette.value[1] } },
    ],
    grid: { left: 50, right: 20, top: 20, bottom: 30 },
  })
}

function initApiTypeChart() {
  if (!apiTypeChartRef.value || !stats.value) return
  const chart = echarts.init(apiTypeChartRef.value, chartTheme.value)
  const dist = stats.value.apiTypeDistribution || {}
  const data = Object.entries(dist).map(([k, v]) => ({ name: k as string, value: v as number }))
  chart.setOption({
    color: chartPalette.value,
    tooltip: { trigger: 'item' },
    series: [{ type: 'pie', radius: ['45%','70%'], data, label: { fontSize: 11 } }],
  })
}

function initToolChart() {
  if (!toolChartRef.value || !stats.value) return
  const chart = echarts.init(toolChartRef.value, chartTheme.value)
  const toolByType = stats.value.toolByType || {}
  const labelMap: Record<string,string> = { knowledgeSearch:'知识库搜索', webSearch:'联网搜索', calculator:'计算器', currentTime:'当前时间', imageRecognition:'图片识别', ocrExtract:'OCR提取', summarizeUrl:'网页摘要' }
  const data = Object.entries(toolByType).map(([k,v]) => ({ name: labelMap[k]||k, value: v }))
  chart.setOption({
    color: chartPalette.value,
    tooltip: { trigger: 'item' },
    series: [{ type: 'pie', radius: ['45%','70%'], data, label: { fontSize: 10 } }],
  })
}
</script>

<style scoped>
.dashboard { padding: 4px 0; }
.page-title { font-size: 20px; font-weight: 600; margin: 0 0 16px; }

.stat-cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 12px; }
.stat-card { display: flex; gap: 12px; align-items: center; padding: 16px; background: var(--bg-card); border: 1px solid var(--horizon-soft); border-radius: 10px; }
.stat-icon { width: 42px; height: 42px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.stat-value { font-size: 22px; font-weight: 700; color: var(--text-primary); }
.stat-label { font-size: 12px; color: var(--twilight); margin-top: 2px; }

.chart-card { background: var(--bg-card); border: 1px solid var(--horizon-soft); border-radius: 10px; padding: 16px; }
.chart-card h3 { font-size: 14px; font-weight: 600; margin: 0 0 8px; color: var(--text-primary); }
.chart-box { width: 100%; height: 260px; }

.recent-list { max-height: 260px; overflow-y: auto; }
.recent-item { display: flex; align-items: center; gap: 8px; padding: 7px 0; border-bottom: 1px solid var(--horizon-soft); font-size: 12px; }
.recent-user { color: var(--pulsar); font-weight: 600; flex-shrink: 0; }
.recent-text { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-secondary); }
.recent-time { color: var(--twilight); flex-shrink: 0; font-size: 11px; }
.recent-empty { text-align: center; padding: 40px 0; color: var(--twilight); font-size: 13px; }
</style>
