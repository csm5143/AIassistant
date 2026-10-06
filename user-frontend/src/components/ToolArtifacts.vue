<template>
  <div v-if="assets.length" class="tool-artifacts">
    <article v-for="asset in assets" :key="asset.id" class="artifact-card">
      <div class="artifact-icon"><el-icon><Picture v-if="asset.mime === 'image/png'" /><Grid v-else-if="isTable(asset)" /><Document v-else /></el-icon></div>
      <div class="artifact-info"><strong :title="asset.name">{{ asset.name }}</strong><span>{{ assetType(asset) }} · {{ size(asset.size) }}</span></div>
      <div class="artifact-actions">
        <button v-if="canPreview(asset)" :disabled="loading === asset.id" @click="preview(asset)" :aria-label="`预览 ${asset.name}`">预览</button>
        <button :disabled="loading === asset.id" @click="download(asset)" :aria-label="`下载 ${asset.name}`">下载</button>
      </div>
    </article>
    <el-dialog v-model="visible" :title="current?.name || '成果预览'" width="min(960px, 94vw)" append-to-body @closed="release">
      <div v-loading="!!loading" class="artifact-preview">
        <img v-if="current?.mime === 'image/png' && previewUrl" :src="previewUrl" :alt="current.name" />
        <iframe v-else-if="current?.mime === 'application/pdf' && previewUrl" :src="previewUrl" title="PDF 成果预览" />
        <template v-else-if="tablePreview">
          <div class="table-preview-meta"><span>{{ tablePreview.rowCount }} 行 · 仅显示前 5 行</span><el-select v-if="tablePreview.sheets.length > 1" v-model="sheet" @change="previewSheet" size="small" aria-label="选择工作表"><el-option v-for="name in tablePreview.sheets" :key="name" :label="name" :value="name" /></el-select></div>
          <el-table :data="tablePreview.sample" max-height="480" border><el-table-column v-for="(col, i) in tablePreview.columns" :key="col.name" :label="col.name" min-width="130"><template #default="{ row }">{{ row[i] }}</template></el-table-column></el-table>
        </template>
      </div>
      <template #footer><el-button @click="visible = false">关闭</el-button><el-button type="primary" @click="current && download(current)">下载文件</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { ref, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Document, Grid, Picture } from '@element-plus/icons-vue'
import { type ToolAsset, downloadToolFile, toolFileBlob, executeFileTool } from '@/api/workspaceTools'
defineProps<{ assets: ToolAsset[] }>()
const loading = ref(''), visible = ref(false), current = ref<ToolAsset | null>(null), previewUrl = ref(''), sheet = ref('')
const tablePreview = ref<{ rowCount: number; sheets: string[]; columns: { name: string }[]; sample: string[][] } | null>(null)
const isTable = (a: ToolAsset) => /\.(xlsx|xls|csv)$/i.test(a.name)
const canPreview = (a: ToolAsset) => isTable(a) || ['image/png', 'application/pdf'].includes(a.mime)
const assetType = (a: ToolAsset) => a.mime === 'image/png' ? '图表' : isTable(a) ? '表格' : a.mime === 'application/pdf' ? 'PDF' : 'Word 文档'
const size = (n: number) => n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(n / 1024))} KB`
function release() { if (previewUrl.value) URL.revokeObjectURL(previewUrl.value); previewUrl.value = ''; tablePreview.value = null }
async function download(asset: ToolAsset) { loading.value = asset.id; try { await downloadToolFile(asset) } catch (e: any) { ElMessage.error(e.response?.data?.message || e.message || '下载失败') } finally { loading.value = '' } }
async function previewSheet() {
  if (!current.value) return
  loading.value = current.value.id
  try { const { data } = await executeFileTool(current.value.sessionId, 'inspectTable', { fileId: current.value.id, sheet: sheet.value }); tablePreview.value = data.data; sheet.value = data.data.sheet } catch(e: any) { ElMessage.error(e.response?.data?.message || e.message || '表格预览失败') } finally { loading.value = '' }
}
async function preview(asset: ToolAsset) {
  release(); current.value = asset; visible.value = true; loading.value = asset.id; sheet.value = ''
  try { if (isTable(asset)) await previewSheet(); else previewUrl.value = URL.createObjectURL(await toolFileBlob(asset)) }
  catch (e: any) { ElMessage.error(e.response?.data?.message || e.message || '预览失败'); visible.value = false }
  finally { loading.value = '' }
}
onUnmounted(release)
</script>
<style scoped>
.tool-artifacts { display: grid; gap: 8px; margin-top: 12px; }
.artifact-card { display: flex; align-items: center; gap: 12px; padding: 14px; border: 1px solid #454545; background: #171717; border-radius: 13px; color: #f5f5f5; min-width: 0; }
.artifact-icon { width: 36px; height: 36px; display: grid; place-items: center; background: #303030; border-radius: 9px; flex-shrink: 0; font-size: 20px; }
.artifact-info { flex: 1; min-width: 0; display: grid; gap: 5px; }.artifact-info strong { font-weight: 600; font-size: 13px; overflow-wrap: anywhere; }.artifact-info span { font-size: 12px; color: #c4c4c4; }
.artifact-actions { display: flex; gap: 6px; flex-shrink: 0; }.artifact-actions button { color: #fff; background: #303030; border: 1px solid #646464; padding: 7px 11px; border-radius: 8px; cursor: pointer; }.artifact-actions button:hover { background: #494949; }.artifact-actions button:focus-visible { outline: 2px solid white; outline-offset: 2px; }
.artifact-preview { min-height: 100px; }.artifact-preview img { width: 100%; height: auto; background: white; }.artifact-preview iframe { width: 100%; height: 68vh; border: 0; background: #fff; }.table-preview-meta { display: flex; align-items: center; justify-content: space-between; gap: 15px; margin-bottom: 14px; color: #ddd; font-size: 13px; }.table-preview-meta .el-select { width: 180px; }
@media(max-width: 600px) { .artifact-card { flex-wrap: wrap; }.artifact-info { flex-basis: calc(100% - 50px); }.artifact-actions { margin-left: 48px; } }
</style>
