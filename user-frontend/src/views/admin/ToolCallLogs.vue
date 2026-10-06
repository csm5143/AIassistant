<template>
  <div class="tcl-page">
    <div class="page-header">
      <h1>工具调用日志</h1>
      <p class="page-desc">记录 Agent 每次工具调用的详情和执行耗时</p>
    </div>

    <!-- Filters -->
    <div class="tcl-filters">
      <el-input v-model="filters.sessionId" placeholder="会话ID" clearable style="width:200px" />
      <el-select v-model="filters.toolName" placeholder="工具名称" clearable style="width:150px">
        <el-option v-for="t in toolNames" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
      <el-select v-model="filters.status" placeholder="状态" clearable style="width:120px">
        <el-option label="成功" value="success" />
        <el-option label="失败" value="failed" />
      </el-select>
      <el-date-picker v-model="filters.dateRange" type="daterange" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" value-format="YYYY-MM-DD" style="width:260px" />
      <el-button type="primary" @click="fetchLogs">查询</el-button>
      <el-button @click="exportCSV">导出 CSV</el-button>
    </div>

    <el-table :data="logs" stripe v-loading="loading">
      <el-table-column prop="sessionId" label="会话ID" width="160" show-overflow-tooltip />
      <el-table-column prop="toolName" label="工具" width="130">
        <template #default="{ row }">{{ toolLabel(row.toolName) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="70" align="center">
        <template #default="{ row }">
          <span :class="row.status === 'success' ? 'status-ok' : 'status-fail'">{{ row.status === 'success' ? '✅' : '❌' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="80" align="right">
        <template #default="{ row }">{{ row.elapsedMs }}ms</template>
      </el-table-column>
      <el-table-column prop="arguments" label="参数" min-width="160" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="时间" width="160" />
      <el-table-column label="操作" width="80" align="center">
        <template #default="{ row }">
          <el-button size="small" @click="showDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="total > 0" style="margin-top:16px;justify-content:flex-end"
      v-model:current-page="page" :page-size="size" :total="total"
      layout="total, prev, pager, next" @current-change="fetchLogs"
    />

    <!-- Detail dialog -->
    <el-dialog v-model="detailVisible" title="工具调用详情" width="600px">
      <div v-if="detail" class="detail-body">
        <div class="detail-row"><label>工具</label><span>{{ toolLabel(detail.toolName) }}</span></div>
        <div class="detail-row"><label>状态</label><span :class="detail.status === 'success' ? 'status-ok' : 'status-fail'">{{ detail.status }}</span></div>
        <div class="detail-row"><label>耗时</label><span>{{ detail.elapsedMs }}ms</span></div>
        <div class="detail-row"><label>会话ID</label><span class="mono">{{ detail.sessionId }}</span></div>
        <div class="detail-row"><label>时间</label><span>{{ detail.createdAt }}</span></div>
        <div class="detail-field"><label>参数</label><pre>{{ formatJson(detail.arguments) }}</pre></div>
        <div class="detail-field"><label>结果</label><pre>{{ detail.result }}</pre></div>
        <div v-if="detail.errorMessage" class="detail-field"><label>错误</label><pre class="err">{{ detail.errorMessage }}</pre></div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import api from '@/api'
import { ElMessage } from 'element-plus'

interface LogRow {
  id: string; sessionId: string; toolName: string; arguments: string;
  result: string; status: string; errorMessage: string | null;
  elapsedMs: number; createdAt: string;
}

const logs = ref<LogRow[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)

const filters = ref({ sessionId: '', toolName: '', status: '', dateRange: null as string[] | null })

const toolNames = [
  { value: 'knowledgeSearch', label: '知识库搜索' },
  { value: 'webSearch', label: '联网搜索' },
  { value: 'calculator', label: '计算器' },
  { value: 'currentTime', label: '当前时间' },
  { value: 'imageRecognition', label: '图片识别' },
  { value: 'ocrExtract', label: 'OCR提取' },
]

// Detail
const detail = ref<LogRow | null>(null)
const detailVisible = ref(false)

onMounted(() => fetchLogs())

async function fetchLogs() {
  loading.value = true
  try {
    const params: any = { page: page.value, size: size.value }
    if (filters.value.sessionId) params.sessionId = filters.value.sessionId
    if (filters.value.toolName) params.toolName = filters.value.toolName
    if (filters.value.status) params.status = filters.value.status
    if (filters.value.dateRange) {
      params.startDate = filters.value.dateRange[0]
      params.endDate = filters.value.dateRange[1]
    }
    const { data } = await api.get('/admin/tool-logs', { params })
    const pageData = data.data
    logs.value = pageData.records || []
    total.value = pageData.total || 0
  } catch { ElMessage.error('加载失败') }
  finally { loading.value = false }
}

function showDetail(row: LogRow) { detail.value = row; detailVisible.value = true }

function toolLabel(name: string) {
  const m: Record<string, string> = {
    knowledgeSearch:'知识库搜索', webSearch:'联网搜索', calculator:'计算器',
    currentTime:'当前时间', imageRecognition:'图片识别', ocrExtract:'OCR提取',
  }
  return m[name] || name
}

function formatJson(s: string) {
  try { return JSON.stringify(JSON.parse(s), null, 2) }
  catch { return s }
}

function exportCSV() {
  if (logs.value.length === 0) { ElMessage.warning('无数据可导出'); return }
  const header = '会话ID,工具,状态,耗时ms,参数,结果,时间\n'
  const rows = logs.value.map(r =>
    `"${r.sessionId}","${toolLabel(r.toolName)}","${r.status}",${r.elapsedMs},"${r.arguments?.replace(/"/g,'""') || ''}","${r.result?.replace(/"/g,'""') || ''}","${r.createdAt}"`
  ).join('\n')
  const blob = new Blob(['﻿' + header + rows], { type: 'text/csv;charset=UTF-8' })
  const a = document.createElement('a'); a.href = URL.createObjectURL(blob)
  a.download = 'tool-call-logs.csv'; a.click(); URL.revokeObjectURL(a.href)
}
</script>

<style scoped>
.tcl-page { padding: 4px 0; }
.page-header h1 { font-size: 20px; font-weight: 600; margin: 0 0 4px; }
.page-desc { font-size: 13px; color: var(--twilight); margin: 0 0 12px; }

.tcl-filters { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }

.status-ok { color: #b0b0b0; } .status-fail { color: #b0b0b0; }

.detail-body { display: flex; flex-direction: column; gap: 10px; }
.detail-row { display: flex; gap: 12px; align-items: center; }
.detail-row label { font-size: 12px; font-weight: 600; color: var(--twilight); width: 60px; flex-shrink: 0; }
.detail-field label { font-size: 12px; font-weight: 600; color: var(--twilight); display: block; margin-bottom: 4px; }
.detail-field pre {
  background: var(--bg-subtle); border-radius: 6px; padding: 10px; font-size: 12px;
  max-height: 200px; overflow-y: auto; white-space: pre-wrap; word-break: break-all; margin: 0;
}
.detail-field pre.err { color: #b0b0b0; background: #f5f5f5; }
.mono { font-family: 'JetBrains Mono', monospace; font-size: 12px; }
</style>
