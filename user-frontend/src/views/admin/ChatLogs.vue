<template>
  <div class="logs-page">
    <div class="page-header">
      <h1 class="page-title">对话日志</h1>
      <div class="header-actions">
        <el-button :icon="Download" @click="showExportDialog = true">导出</el-button>
        <el-button :icon="Refresh" @click="fetchData">刷新</el-button>
      </div>
    </div>

    <div class="filters">
      <el-input v-model="filters.userId" placeholder="用户ID" clearable style="width:180px" @change="fetchData"/>
      <el-date-picker v-model="filters.startDate" type="date" placeholder="开始日期" value-format="YYYY-MM-DD" @change="fetchData"/>
      <el-date-picker v-model="filters.endDate" type="date" placeholder="结束日期" value-format="YYYY-MM-DD" @change="fetchData"/>
    </div>

    <el-table :data="logs" stripe style="width:100%" v-loading="loading">
      <el-table-column prop="userId" label="用户" width="140" />
      <el-table-column prop="question" label="问题" min-width="200" show-overflow-tooltip />
      <el-table-column prop="answer" label="回答" min-width="250" show-overflow-tooltip />
      <el-table-column prop="model" label="模型" width="120" />
      <el-table-column prop="promptTokens" label="输入 Token" width="100" align="right" />
      <el-table-column prop="completionTokens" label="输出 Token" width="100" align="right" />
      <el-table-column prop="latencyMs" label="耗时(ms)" width="90" align="right" />
      <el-table-column label="详情" width="70" fixed="right">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="previewLog(row)">预览</el-button>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
    </el-table>

    <div class="pagination">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        @change="fetchData"
      />
    </div>

    <!-- Preview Dialog -->
    <el-dialog v-model="showPreview" title="对话详情" width="700px" top="5vh">
      <div class="preview-section">
        <div class="preview-row"><span class="preview-label">用户ID</span><span>{{ previewData?.userId }}</span></div>
        <div class="preview-row"><span class="preview-label">模型</span><span>{{ previewData?.model }}</span></div>
        <div class="preview-row"><span class="preview-label">时间</span><span>{{ formatTime(previewData?.createdAt || '') }}</span></div>
        <div class="preview-row"><span class="preview-label">Token</span><span>输入 {{ previewData?.promptTokens || 0 }} / 输出 {{ previewData?.completionTokens || 0 }}</span></div>
        <div class="preview-row"><span class="preview-label">耗时</span><span>{{ previewData?.latencyMs }}ms</span></div>
        <div class="preview-block">
          <div class="preview-label">问题</div>
          <div class="preview-content">{{ previewData?.question }}</div>
        </div>
        <div class="preview-block">
          <div class="preview-label">回答</div>
          <div class="preview-content">{{ previewData?.answer }}</div>
        </div>
      </div>
    </el-dialog>

    <!-- Export Dialog -->
    <el-dialog v-model="showExportDialog" title="导出对话日志" width="400px">
      <el-form label-width="80px">
        <el-form-item label="导出格式">
          <el-radio-group v-model="exportFormat">
            <el-radio value="json">JSON</el-radio>
            <el-radio value="csv">CSV</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="导出范围">
          <el-radio-group v-model="exportScope">
            <el-radio value="current">当前页 ({{ logs.length }} 条)</el-radio>
            <el-radio value="all">全部 ({{ total }} 条)</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showExportDialog = false">取消</el-button>
        <el-button type="primary" @click="doExport">导出</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, reactive } from 'vue'
import { getChatLogs, type ChatLog } from '@/api'
import { Refresh, Download } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

const logs = ref<ChatLog[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)
const filters = reactive<{ userId?: string; startDate?: string; endDate?: string }>({})
const showPreview = ref(false)
const previewData = ref<ChatLog | null>(null)
const showExportDialog = ref(false)
const exportFormat = ref<'json' | 'csv'>('json')
const exportScope = ref<'current' | 'all'>('current')

onMounted(() => fetchData())

async function fetchData() {
  loading.value = true
  try {
    const { data } = await getChatLogs({ page: page.value, size: size.value, ...filters })
    logs.value = data.data.records || []
    total.value = data.data.total || 0
  } finally { loading.value = false }
}

function formatTime(t: string) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

function previewLog(log: ChatLog) {
  previewData.value = log
  showPreview.value = true
}

async function doExport() {
  try {
    let data: ChatLog[]
    if (exportScope.value === 'all') {
      const res = await getChatLogs({ page: 1, size: Math.min(total.value, 10000), ...filters })
      data = res.data.data.records || []
    } else {
      data = logs.value
    }

    if (data.length === 0) { ElMessage.warning('没有可导出的数据'); return }

    let content: string
    let filename: string
    let mime: string

    if (exportFormat.value === 'csv') {
      const headers = ['用户ID', '问题', '回答', '模型', '输入Token', '输出Token', '耗时(ms)', '时间']
      const rows = data.map(l => [
        l.userId, `"${(l.question || '').replace(/"/g, '""')}"`,
        `"${(l.answer || '').replace(/"/g, '""')}"`, l.model,
        l.promptTokens || 0, l.completionTokens || 0, l.latencyMs || 0, l.createdAt
      ])
      content = '﻿' + [headers.join(','), ...rows.map(r => r.join(','))].join('\n')
      filename = `chat-logs-${new Date().toISOString().slice(0, 10)}.csv`
      mime = 'text/csv'
    } else {
      content = JSON.stringify(data.map(l => ({
        userId: l.userId, question: l.question, answer: l.answer, model: l.model,
        promptTokens: l.promptTokens, completionTokens: l.completionTokens,
        latencyMs: l.latencyMs, createdAt: l.createdAt
      })), null, 2)
      filename = `chat-logs-${new Date().toISOString().slice(0, 10)}.json`
      mime = 'application/json'
    }

    const blob = new Blob([content], { type: mime })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url; a.download = filename; a.click()
    URL.revokeObjectURL(url)
    ElMessage.success(`已导出 ${data.length} 条记录`)
    showExportDialog.value = false
  } catch {
    ElMessage.error('导出失败')
  }
}
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-title { font-size: 24px; font-weight: 700; margin: 0; }
.header-actions { display: flex; gap: 8px; }
.filters { display: flex; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.pagination { margin-top: 16px; display: flex; justify-content: flex-end; }

.preview-section { font-size: 14px; }
.preview-row { display: flex; gap: 12px; margin-bottom: 10px; align-items: baseline; }
.preview-label { width: 60px; color: var(--text-secondary); flex-shrink: 0; font-weight: 500; }
.preview-block { margin-bottom: 14px; }
.preview-block .preview-label { margin-bottom: 6px; }
.preview-content {
  background: var(--bg-main); border-radius: 8px; padding: 12px 16px;
  white-space: pre-wrap; word-break: break-word; max-height: 200px; overflow-y: auto;
  font-size: 13px; line-height: 1.6;
}
</style>
