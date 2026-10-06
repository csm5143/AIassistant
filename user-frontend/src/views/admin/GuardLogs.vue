<template>
  <div class="logs-page">
    <div class="page-header">
      <h1 class="page-title">安全 & 审计日志</h1>
      <div class="header-actions">
        <el-button :icon="Download" @click="showExportDialog = true">导出</el-button>
        <el-button :icon="Refresh" @click="fetchData">刷新</el-button>
      </div>
    </div>

    <div class="filters">
      <el-select v-model="filters.direction" placeholder="事件类型" clearable style="width:140px" @change="fetchData">
        <el-option label="全部" value="" />
        <el-option label="输入拦截" value="INPUT" />
        <el-option label="输出拦截" value="OUTPUT" />
        <el-option label="登录记录" value="LOGIN" />
        <el-option label="注册记录" value="REGISTER" />
        <el-option label="访问记录" value="ACCESS" />
      </el-select>
      <el-select v-model="filters.stage" placeholder="触发阶段" clearable style="width:120px" @change="fetchData">
        <el-option label="关键词" value="KEYWORD" />
        <el-option label="正则" value="REGEX" />
        <el-option label="用户端" value="user" />
        <el-option label="管理端" value="admin" />
      </el-select>
    </div>

    <el-table :data="logs" stripe style="width:100%" v-loading="loading">
      <el-table-column prop="userId" label="用户" width="140" />
      <el-table-column prop="direction" label="类型" width="110">
        <template #default="{ row }">
          <el-tag :type="getDirectionType(row.direction)" size="small">{{ getDirectionLabel(row.direction) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="stage" label="详情" width="100">
        <template #default="{ row }">{{ row.stage || '-' }}</template>
      </el-table-column>
      <el-table-column prop="rule" label="触发规则" width="140" show-overflow-tooltip />
      <el-table-column prop="action" label="动作" width="80">
        <template #default="{ row }">
          <el-tag :type="getActionType(row.action)" size="small">{{ row.action }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="matchedContent" label="匹配/详情" min-width="200" show-overflow-tooltip />
      <el-table-column label="操作" width="70" fixed="right">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="previewLog(row)">详情</el-button>
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
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @change="fetchData"
      />
    </div>

    <!-- Preview Dialog -->
    <el-dialog v-model="showPreview" title="日志详情" width="560px" top="8vh">
      <div class="preview-section" v-if="previewData">
        <div class="preview-row"><span class="preview-label">事件ID</span><span>{{ previewData.id }}</span></div>
        <div class="preview-row"><span class="preview-label">用户ID</span><span>{{ previewData.userId || '匿名' }}</span></div>
        <div class="preview-row">
          <span class="preview-label">类型</span>
          <el-tag :type="getDirectionType(previewData.direction)" size="small">{{ getDirectionLabel(previewData.direction) }}</el-tag>
        </div>
        <div class="preview-row"><span class="preview-label">触发阶段</span><span>{{ previewData.stage }}</span></div>
        <div class="preview-row"><span class="preview-label">触发规则</span><span>{{ previewData.rule || '-' }}</span></div>
        <div class="preview-row">
          <span class="preview-label">动作</span>
          <el-tag :type="getActionType(previewData.action)" size="small">{{ previewData.action }}</el-tag>
        </div>
        <div class="preview-row"><span class="preview-label">时间</span><span>{{ formatTime(previewData.createdAt) }}</span></div>
        <div class="preview-block" v-if="previewData.matchedContent">
          <div class="preview-label">匹配内容</div>
          <div class="preview-content">{{ previewData.matchedContent }}</div>
        </div>
      </div>
    </el-dialog>

    <!-- Export Dialog -->
    <el-dialog v-model="showExportDialog" title="导出日志" width="400px">
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
import { ref, reactive, onMounted } from 'vue'
import { getGuardLogs, type GuardLog } from '@/api'
import { Refresh, Download } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

const logs = ref<GuardLog[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)
const filters = reactive<{ direction?: string; stage?: string }>({})
const showPreview = ref(false)
const previewData = ref<GuardLog | null>(null)
const showExportDialog = ref(false)
const exportFormat = ref<'json' | 'csv'>('json')
const exportScope = ref<'current' | 'all'>('current')

onMounted(() => fetchData())

async function fetchData() {
  loading.value = true
  try {
    const params: any = { page: page.value, size: size.value }
    if (filters.direction) params.direction = filters.direction
    if (filters.stage) params.stage = filters.stage
    const { data } = await getGuardLogs(params)
    logs.value = data.data.records || []
    total.value = data.data.total || 0
  } finally { loading.value = false }
}

function getDirectionType(d: string) {
  const map: Record<string, string> = { INPUT: 'warning', OUTPUT: 'info', LOGIN: 'success', REGISTER: '', ACCESS: '' }
  return map[d] || 'info'
}

function getDirectionLabel(d: string) {
  const map: Record<string, string> = { INPUT: '输入拦截', OUTPUT: '输出拦截', LOGIN: '登录', REGISTER: '注册', ACCESS: '访问' }
  return map[d] || d
}

function getActionType(a: string) {
  const map: Record<string, string> = { BLOCK: 'danger', MASK: 'warning', ALLOW: 'success' }
  return map[a] || 'info'
}

function formatTime(t: string) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

function previewLog(log: GuardLog) {
  previewData.value = log
  showPreview.value = true
}

async function doExport() {
  try {
    let data: GuardLog[]
    if (exportScope.value === 'all') {
      const params: any = { page: 1, size: Math.min(total.value, 10000) }
      if (filters.direction) params.direction = filters.direction
      if (filters.stage) params.stage = filters.stage
      const res = await getGuardLogs(params)
      data = res.data.data.records || []
    } else {
      data = logs.value
    }

    if (data.length === 0) { ElMessage.warning('没有可导出的数据'); return }

    let content: string; let filename: string; let mime: string

    if (exportFormat.value === 'csv') {
      const headers = ['ID', '用户ID', '类型', '阶段', '规则', '动作', '匹配内容', '时间']
      const rows = data.map(l => [
        l.id, l.userId || '', getDirectionLabel(l.direction),
        l.stage, l.rule || '', l.action,
        `"${(l.matchedContent || '').replace(/"/g, '""')}"`, l.createdAt
      ])
      content = '﻿' + [headers.join(','), ...rows.map(r => r.join(','))].join('\n')
      filename = `audit-logs-${new Date().toISOString().slice(0, 10)}.csv`
      mime = 'text/csv'
    } else {
      content = JSON.stringify(data.map(l => ({
        id: l.id, userId: l.userId, direction: getDirectionLabel(l.direction),
        stage: l.stage, rule: l.rule, action: l.action,
        matchedContent: l.matchedContent, createdAt: l.createdAt
      })), null, 2)
      filename = `audit-logs-${new Date().toISOString().slice(0, 10)}.json`
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
.filters { display: flex; gap: 12px; margin-bottom: 16px; }
.pagination { margin-top: 16px; display: flex; justify-content: flex-end; }

.preview-section { font-size: 14px; }
.preview-row { display: flex; gap: 12px; margin-bottom: 10px; align-items: center; }
.preview-label { width: 70px; color: var(--text-secondary); flex-shrink: 0; font-weight: 500; }
.preview-block { margin-top: 12px; }
.preview-block .preview-label { margin-bottom: 6px; }
.preview-content {
  background: var(--bg-main); border-radius: 8px; padding: 12px 16px;
  white-space: pre-wrap; word-break: break-word; max-height: 160px; overflow-y: auto;
  font-size: 13px; line-height: 1.6;
}
</style>
