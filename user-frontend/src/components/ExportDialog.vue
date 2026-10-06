<template>
  <el-dialog v-model="visible" width="560px" :close-on-click-modal="false" class="export-dialog">
    <template #header>
      <div class="export-header">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
        <span>导出对话</span>
      </div>
    </template>

    <!-- Message list -->
    <div class="export-body">
      <div class="export-section-label">选择要导出的消息</div>

      <div class="export-actions-row">
        <el-checkbox v-model="selectAll" :indeterminate="isIndeterminate" @change="toggleAll">
          全选（{{ selectedCount }}/{{ messages.length }}）
        </el-checkbox>
      </div>

      <div class="export-msg-list">
        <div
          v-for="msg in messages"
          :key="msg.id"
          class="export-msg-item"
          :class="{ selected: checkedIds.has(msg.id) }"
          @click="toggleMsg(msg.id)"
        >
          <el-checkbox :model-value="checkedIds.has(msg.id)" @click.stop @change="toggleMsg(msg.id)" />
          <span class="export-msg-role" :class="msg.role">{{ roleLabel(msg.role) }}</span>
          <span class="export-msg-preview">{{ previewText(msg.content) }}</span>
        </div>
        <div v-if="messages.length === 0" class="export-empty">暂无消息</div>
      </div>

      <!-- Format picker -->
      <div class="export-section-label" style="margin-top: 16px;">选择导出格式</div>
      <div class="export-grid">
        <button
          v-for="fmt in formats"
          :key="fmt.value"
          :class="['export-card', { selected: format === fmt.value }]"
          @click="format = fmt.value"
        >
          <img :src="fmt.icon" :alt="fmt.label" class="export-card-icon" />
          <span class="export-card-label">{{ fmt.label }}</span>
          <span class="export-card-desc">{{ fmt.desc }}</span>
        </button>
      </div>
    </div>

    <template #footer>
      <div class="export-footer">
        <el-button
          :disabled="selectedCount === 0 || exporting"
          :loading="kbExporting"
          round
          @click="handleExportToKB"
        >
          存入知识库
        </el-button>
        <div class="export-footer-right">
          <el-button @click="visible = false" :disabled="exporting || kbExporting" round>取消</el-button>
          <el-button type="primary" @click="handleExport" :loading="exporting" :disabled="selectedCount === 0" round>
            {{ exporting ? '导出中…' : `导出 ${selectedCount} 条消息` }}
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '@/api'

interface Message {
  id: string
  role: string
  content: string
  createdAt: string
}

const props = defineProps<{
  modelValue: boolean
  sessionId: string
  messages: Message[]
}>()

const emit = defineEmits<{ 'update:modelValue': [v: boolean] }>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const checkedIds = ref<Set<string>>(new Set())
const format = ref('md')
const exporting = ref(false)
const kbExporting = ref(false)

const formats = [
  { value: 'txt',  label: 'TXT',  desc: '纯文本',  icon: '/txt.svg' },
  { value: 'md',   label: 'MD',   desc: 'Markdown 格式，保留排版', icon: '/md.svg' },
  { value: 'pdf',  label: 'PDF',  desc: '便携文档，适合打印', icon: '/PDF.svg' },
  { value: 'word', label: 'Word', desc: '可编辑的 .docx 文档', icon: '/WORD.svg' },
]

const selectedCount = computed(() => checkedIds.value.size)

const selectAll = computed({
  get: () => props.messages.length > 0 && checkedIds.value.size === props.messages.length,
  set: () => {},
})

const isIndeterminate = computed(
  () => checkedIds.value.size > 0 && checkedIds.value.size < props.messages.length
)

// Reset state on open
watch(visible, (v) => {
  if (v) {
    checkedIds.value = new Set(props.messages.map(m => m.id))
    format.value = 'md'
  }
})

function toggleAll() {
  if (checkedIds.value.size === props.messages.length) {
    checkedIds.value = new Set()
  } else {
    checkedIds.value = new Set(props.messages.map(m => m.id))
  }
}

function toggleMsg(id: string) {
  const next = new Set(checkedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  checkedIds.value = next
}

function roleLabel(role: string) {
  if (role === 'user') return '用户'
  if (role === 'assistant') return 'AI'
  return '系统'
}

function previewText(content: string) {
  if (!content) return ''
  const clean = content
    .replace(/```[\s\S]*?```/g, '[代码]')
    .replace(/\n/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return clean.length > 60 ? clean.substring(0, 60) + '…' : clean
}

async function handleExportToKB() {
  if (checkedIds.value.size === 0) {
    ElMessage.warning('请至少选择一条消息')
    return
  }
  kbExporting.value = true
  try {
    const { data } = await api.post('/chat/export-to-knowledge', {
      sessionId: props.sessionId,
      messageIds: [...checkedIds.value],
    })
    if (data.code === 200) {
      const d = data.data
      ElMessage.success(`已存入知识库「${d.title}」(${d.messageCount} 条消息, ${d.chars} 字)`)
      visible.value = false
    } else {
      ElMessage.error(data.message || '存入知识库失败')
    }
  } catch (e: any) {
    const msg = e.response?.data?.message || e.message || '存入知识库失败'
    ElMessage.error(msg)
  } finally {
    kbExporting.value = false
  }
}

async function handleExport() {
  if (checkedIds.value.size === 0) {
    ElMessage.warning('请至少选择一条消息')
    return
  }
  exporting.value = true
  try {
    const resp = await api.post('/chat/export', {
      sessionId: props.sessionId,
      format: format.value,
      messageIds: [...checkedIds.value],
    }, { responseType: 'blob' })
    const blob = resp.data as Blob
    const disposition = (resp.headers['content-disposition'] || '') as string
    const extMap: Record<string, string> = { txt: 'txt', md: 'md', pdf: 'pdf', word: 'docx' }
    const ext = extMap[format.value] || format.value
    const match = disposition.match(/filename\*?=(?:UTF-8'')?(.+)/)
    const filename = match ? decodeURIComponent(match[1]) : `对话导出.${ext}`
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
    visible.value = false
  } catch (e: any) {
    const msg = e.response?.data?.message || e.message || '导出失败'
    ElMessage.error(msg)
  } finally {
    exporting.value = false
  }
}
</script>

<style scoped>
.export-header { display: flex; align-items: center; gap: 8px; font-size: 16px; font-weight: 600; }

.export-body { padding: 4px 0 0; }

.export-section-label {
  font-size: 12px; font-weight: 600; color: var(--twilight);
  margin-bottom: 8px; text-transform: uppercase; letter-spacing: 0.04em;
}

.export-actions-row { margin-bottom: 8px; }

.export-msg-list {
  max-height: 280px; overflow-y: auto;
  border: 1px solid var(--horizon-soft); border-radius: 8px;
}

.export-msg-item {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 12px; cursor: pointer;
  border-bottom: 1px solid var(--horizon-soft);
  transition: background 0.15s;
  font-size: 13px;
}
.export-msg-item:last-child { border-bottom: none; }
.export-msg-item:hover { background: var(--bg-subtle); }
.export-msg-item.selected { background: var(--pulsar-glow); }

.export-msg-role {
  flex-shrink: 0; font-weight: 600; font-size: 11px;
  padding: 1px 7px; border-radius: 4px;
}
.export-msg-role.user { background: #3c3c3c; color: #e0e0e0; }
.export-msg-role.assistant { background: #3c3c3c; color: #cccccc; }
.export-msg-role.system { background: #3c3c3c; color: #cccccc; }

.export-msg-preview {
  flex: 1; overflow: hidden; text-overflow: ellipsis;
  white-space: nowrap; color: var(--text-secondary);
}

.export-empty { text-align: center; padding: 32px; color: var(--twilight); font-size: 13px; }

/* Format cards — reuse existing design */
.export-grid {
  display: grid; grid-template-columns: 1fr 1fr; gap: 10px;
}
.export-card {
  display: flex; flex-direction: column; align-items: center; gap: 4px;
  padding: 16px 12px 12px;
  border: 1.5px solid var(--horizon-soft); border-radius: 10px;
  background: var(--bg-card); cursor: pointer;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  font-family: inherit;
}
.export-card:hover {
  border-color: var(--pulsar); background: rgba(224,224,224, 0.03);
  transform: translateY(-2px); box-shadow: 0 4px 12px rgba(224,224,224, 0.08);
}
.export-card.selected {
  border-color: var(--pulsar); background: var(--pulsar-glow);
  box-shadow: 0 0 0 3px rgba(224,224,224, 0.1);
}
.export-card:active { transform: scale(0.96); }
.export-card-icon { width: 32px; height: 32px; object-fit: contain; }
.export-card-label { font-size: 14px; font-weight: 600; color: var(--text-primary); }
.export-card-desc { font-size: 11px; color: var(--twilight); }

.export-footer { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.export-footer-right { display: flex; gap: 8px; }
</style>
