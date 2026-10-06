<template>
  <el-drawer :model-value="visible" title="文件与成果" size="min(590px, 96vw)" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="emit('update:visible', $event)">
    <div class="workspace-tools">
      <p class="tools-intro">上传文件后，直接在聊天中描述任务。需要精确设置时，可以展开手动操作。</p>
      <label class="tool-upload" :class="{ disabled: busy || streaming }"><el-icon><UploadFilled /></el-icon><div><strong>{{ uploading ? '正在上传…' : '添加文件' }}</strong><span>文档 ≤ 100 MB · 表格 ≤ 20 MB</span></div><input type="file" multiple accept=".xlsx,.xls,.csv,.pdf,.docx,.txt,.md" hidden :disabled="busy || streaming" @change="upload" /></label>
      <div class="chat-task-entry"><el-input v-model="chatTask" :disabled="busy || streaming" placeholder="例如：合并这些 PDF，或按月统计支出" maxlength="2000" @keydown.enter.prevent="submitTask" /><el-button :disabled="busy || streaming || !chatTask.trim()" @click="submitTask">交给 AI</el-button></div>
      <div class="tool-files" v-if="uploads.length">
        <p class="files-label">选择要处理的文件 <span>{{ selected.length }} / {{ uploads.length }}</span></p>
        <div v-for="file in uploads" :key="file.id" class="tool-file-row">
          <el-checkbox :model-value="selected.includes(file.id)" :disabled="busy || streaming" @change="toggle(file.id)" :aria-label="`选择 ${file.name}`" />
          <span class="file-name" :title="file.name">{{ file.name }}</span><span class="file-size">{{ (file.size / 1024 / 1024).toFixed(1) }} MB</span>
          <button class="file-remove" :disabled="busy || streaming" @click="remove(file)" :aria-label="`移除 ${file.name}`">移除</button>
        </div>
      </div>
      <button class="manual-toggle" :aria-expanded="advanced" @click="advanced = !advanced">{{ advanced ? '收起手动操作' : '手动操作' }} <span aria-hidden="true">{{ advanced ? '⌃' : '⌄' }}</span></button>
      <el-tabs v-if="advanced" v-model="tab" class="tool-tabs">
        <el-tab-pane label="表格分析" name="table">
          <div class="tool-form">
            <p class="tool-description">精确计算并输出 Excel 和图表。第一行需为表头。</p>
            <el-select v-model="tableFile" placeholder="选择表格" :disabled="busy || streaming" @change="sheet = ''; inspect()"><el-option v-for="file in uploads.filter(isTable)" :key="file.id" :value="file.id" :label="file.name" /></el-select>
            <template v-if="profile">
              <div class="table-profile"><span>{{ profile.rowCount }} 行 · {{ profile.columns.length }} 列</span><el-select v-model="sheet" size="small" :disabled="busy || streaming" @change="inspect"><el-option v-for="name in profile.sheets" :key="name" :label="name" :value="name" /></el-select></div>
              <label>统计列</label><el-select v-model="valueColumn" placeholder="选择数值列" :disabled="busy || streaming"><el-option v-for="c in profile.columns" :key="c.name" :label="`${c.name}（${c.numeric} 个数值）`" :value="c.name" /></el-select>
              <div class="form-pair"><div><label>计算方式</label><el-select v-model="aggregate" :disabled="busy || streaming"><el-option v-for="(name, key) in aggregateLabels" :key="key" :label="name" :value="key" /></el-select></div><div><label>图表</label><el-select v-model="chartType" :disabled="busy || streaming"><el-option label="柱状图" value="bar" /><el-option label="折线图" value="line" /><el-option label="不生成图表" value="none" /></el-select></div></div>
              <label>分组列（可选）</label><el-select v-model="groupBy" multiple clearable placeholder="例如类别、部门" :disabled="busy || streaming"><el-option v-for="c in profile.columns" :key="c.name" :value="c.name" :label="c.name" /></el-select>
              <label>按月统计（可选）</label><el-select v-model="dateColumn" clearable placeholder="选择日期列" :disabled="busy || streaming"><el-option v-for="c in profile.columns" :key="c.name" :value="c.name" :label="c.name" /></el-select>
              <el-checkbox v-model="deduplicate" :disabled="busy || streaming">移除完全重复的行</el-checkbox>
              <p class="tool-note">空值、非数字会单独计数，不作为 0 参与统计；公式仅读取已保存的结果。图表展示前 20 条，完整结果在 Excel 中。</p>
              <el-button type="primary" :loading="busy" :disabled="streaming || aggregate !== 'count' && !valueColumn" @click="analyze">计算并生成成果</el-button>
            </template>
            <el-button :disabled="busy || streaming || !uploads.some(isTable)" @click="askTable">交给 AI 自定义分析</el-button>
          </div>
        </el-tab-pane>
        <el-tab-pane label="制作报告" name="report">
          <div class="tool-form">
            <p class="tool-description">整理内容后生成独立报告，支持标题、列表、表格与本对话图表。</p>
            <label>报告标题</label><el-input v-model="title" maxlength="120" :disabled="busy || streaming" placeholder="例如 每月消费分析报告" />
            <label>正文</label><el-input v-model="markdown" type="textarea" :rows="9" maxlength="60000" :disabled="busy || streaming" placeholder="输入正文或 Markdown。也可以先在对话中让 AI 起草。" />
            <button v-if="lastAnswer" class="text-action" :disabled="busy || streaming" @click="markdown = lastAnswer">使用上一条 AI 回答</button>
            <el-checkbox-group v-model="formats" :disabled="busy || streaming"><el-checkbox label="docx">Word</el-checkbox><el-checkbox label="pdf">PDF</el-checkbox></el-checkbox-group>
            <el-checkbox v-if="assets.some(a => a.kind === 'chart')" v-model="includeChart" :disabled="busy || streaming">附上最新生成的图表</el-checkbox>
            <el-button type="primary" :loading="busy" :disabled="streaming || !title.trim() || !markdown.trim() || !formats.length" @click="makeReport">生成报告</el-button>
            <el-button :disabled="busy || streaming" @click="ask('请根据当前对话已核实的内容制作一份结构清晰的报告，生成可下载的 Word 和 PDF；有已生成图表时请嵌入图表，保留来源和必要的限制说明。')">让 AI 起草并生成报告</el-button>
          </div>
        </el-tab-pane>
        <el-tab-pane label="批量提取" name="extract">
          <div class="tool-form">
            <p class="tool-description">选中多份文档，填写字段。AI 读取原文后汇总为 Excel，保留原文位置与核验结果。</p>
            <label>提取字段（用逗号分隔）</label><el-input v-model="fieldsText" :disabled="busy || streaming" placeholder="例如 论文标题，作者，方法，主要结论" />
            <p class="tool-note">支持 PDF / Word / TXT / MD。仅核验通过的字段写入值；缺少证据的字段留空。Word 显示文本段位置，不伪造页码。</p>
            <el-button type="primary" :disabled="busy || streaming || !selectedDocs.length || !fieldsText.trim()" @click="extract">在对话中提取并核验</el-button>
          </div>
        </el-tab-pane>
        <el-tab-pane label="PDF 整理" name="pdf">
          <div class="tool-form">
            <p class="tool-description">选择 PDF，合并、提取、拆分或旋转页面。合并顺序按下方列表排列。</p>
            <div v-if="selectedPdfs.length" class="pdf-order"><div v-for="(file, i) in selectedPdfs" :key="file.id"><span>{{ i + 1 }}. {{ file.name }}</span><button v-if="i > 0" :disabled="busy || streaming" @click="moveUp(file.id)">上移</button></div></div>
            <label>操作</label><el-select v-model="operation" :disabled="busy || streaming"><el-option label="合并文件" value="MERGE" /><el-option label="提取页面" value="EXTRACT" /><el-option label="按页拆分" value="SPLIT" /><el-option label="旋转页面" value="ROTATE" /></el-select>
            <template v-if="operation !== 'MERGE'"><label>页码（从 1 开始）</label><el-input v-model="pages" :disabled="busy || streaming" :placeholder="operation === 'SPLIT' ? '例如 1-3;4-6，每组生成一份文件' : '例如 1-3,5；留空为全部页'" /></template>
            <template v-if="operation === 'ROTATE'"><label>顺时针旋转</label><el-select v-model="rotation" :disabled="busy || streaming"><el-option v-for="n in [90, 180, 270]" :key="n" :label="`${n}°`" :value="n" /></el-select></template>
            <p class="tool-note">单个输出最多 500 页；保留页面内容与尺寸。书签、附件、批注不会写入成果，含交互表单或签名的 PDF 需先另存普通 PDF。</p>
            <el-button type="primary" :loading="busy" :disabled="streaming || !selectedPdfs.length || operation !== 'MERGE' && selectedPdfs.length !== 1 || operation === 'SPLIT' && !pages.trim()" @click="processPdf">处理并生成 PDF</el-button>
          </div>
        </el-tab-pane>
      </el-tabs>
      <div v-if="busy" class="tool-progress" role="status"><el-icon class="is-loading"><Loading /></el-icon>{{ progress }}</div>
      <p v-if="error" class="tool-error" role="alert">{{ error }}</p>
      <section v-if="result" class="tool-result">
        <strong>{{ result.summary }}</strong><span v-if="result.elapsedMs != null">{{ (result.elapsedMs / 1000).toFixed(2) }} 秒</span>
        <p v-if="result.audit?.invalidNumericCells && Object.values(result.audit.invalidNumericCells as Record<string, number>).some(n => n > 0)" class="tool-note">存在非数字单元格，已跳过并在 Excel 的“处理说明”中记录。</p>
        <ToolArtifacts :assets="result.artifacts || []" />
        <el-table v-if="result.preview?.length" :data="result.preview" max-height="260" border><el-table-column v-for="(col, i) in result.columns" :key="col" :label="col" min-width="125"><template #default="{ row }">{{ row[i] ?? '—' }}</template></el-table-column></el-table>
      </section>
      <section v-if="generated.length" class="recent-artifacts"><h3>本对话成果</h3><ToolArtifacts :assets="generated" /></section>
    </div>
  </el-drawer>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled, Loading } from '@element-plus/icons-vue'
import ToolArtifacts from './ToolArtifacts.vue'
import { type ToolAsset, type WorkspaceResult, listToolFiles, uploadToolFile, removeToolFile, executeFileTool } from '@/api/workspaceTools'
const props = defineProps<{ visible: boolean; sessionId: string | null; streaming: boolean; lastAnswer: string }>()
const emit = defineEmits<{ (e: 'update:visible', value: boolean): void; (e: 'files', files: ToolAsset[]): void; (e: 'ask', prompt: string): void; (e: 'busy', value: boolean): void }>()
const assets = ref<ToolAsset[]>([]), selected = ref<string[]>([]), busy = ref(false), uploading = ref(false), error = ref(''), progress = ref(''), tab = ref('table'), result = ref<WorkspaceResult | null>(null)
const advanced = ref(false), chatTask = ref('')
function submitTask() { if (!busy.value && !props.streaming && chatTask.value.trim()) { const text = chatTask.value.trim(); chatTask.value = ''; ask(text) } }
const uploads = computed(() => assets.value.filter(a => a.kind === 'upload'))
const generated = computed(() => assets.value.filter(a => a.kind !== 'upload' && !result.value?.artifacts?.some(r => r.id === a.id)).slice(-12).reverse())
const selectedDocs = computed(() => selected.value.map(id => uploads.value.find(a => a.id === id)).filter((a): a is ToolAsset => !!a && /\.(pdf|docx|txt|md)$/i.test(a.name)))
const selectedPdfs = computed(() => selected.value.map(id => uploads.value.find(a => a.id === id)).filter((a): a is ToolAsset => !!a && /\.pdf$/i.test(a.name)))
const isTable = (a: ToolAsset) => /\.(xls|xlsx|csv)$/i.test(a.name)
const tableFile = ref(''), sheet = ref(''), valueColumn = ref(''), aggregate = ref('sum'), chartType = ref('bar'), groupBy = ref<string[]>([]), dateColumn = ref(''), deduplicate = ref(false)
const profile = ref<{ rowCount: number; sheets: string[]; columns: { name: string; numeric: number }[] } | null>(null)
const aggregateLabels: Record<string, string> = { sum: '求和', avg: '平均值', count: '计数', min: '最小值', max: '最大值' }
const title = ref('分析报告'), markdown = ref(''), formats = ref(['docx', 'pdf']), includeChart = ref(true), fieldsText = ref(''), operation = ref('MERGE'), pages = ref(''), rotation = ref(90)
function toggle(id: string) { selected.value = selected.value.includes(id) ? selected.value.filter(x => x !== id) : [...selected.value, id] }
function moveUp(id: string) { const index = selected.value.indexOf(id); if (index > 0) { const previous = selected.value[index - 1]; selected.value[index - 1] = id; selected.value[index] = previous } }
function message(e: any) { return e.response?.data?.message || e.message || '文件处理失败' }
watch(busy, value => emit('busy', value))
async function load() {
  const session = props.sessionId; if (!session) return
  try { const { data } = await listToolFiles(session); if (session !== props.sessionId) return; assets.value = data.data; selected.value = selected.value.filter(id => assets.value.some(a => a.id === id)); emit('files', assets.value) } catch (e) { error.value = message(e) }
}
watch(() => [props.visible, props.sessionId], () => { if (props.visible) void load() })
watch(() => props.sessionId, () => { assets.value = []; selected.value = []; profile.value = null; tableFile.value = ''; result.value = null; error.value = ''; advanced.value = false; chatTask.value = '' })
async function upload(event: Event) {
  const input = event.target as HTMLInputElement, files = Array.from(input.files || []); input.value = ''; if (!props.sessionId || !files.length) return
  const session = props.sessionId; busy.value = true; uploading.value = true; error.value = ''; progress.value = '上传文件并登记到当前对话…'
  try { for (const file of files) { const limit = /\.(xlsx?|csv)$/i.test(file.name) ? 20 : 100; if (file.size > limit * 1024 * 1024) throw new Error(`${file.name} 超过 ${limit} MB`); const { data } = await uploadToolFile(session, file); if (session === props.sessionId) selected.value.push(data.data.id) } await load() }
  catch (e) { error.value = message(e); await load() }
  finally { busy.value = false; uploading.value = false }
}
async function remove(file: ToolAsset) { busy.value = true; try { await removeToolFile(file.sessionId, file.id); selected.value = selected.value.filter(id => id !== file.id); if (tableFile.value === file.id) { tableFile.value = ''; profile.value = null } await load() } catch (e) { error.value = message(e) } finally { busy.value = false } }
async function inspect() {
  if (!props.sessionId || !tableFile.value) return
  busy.value = true; error.value = ''; progress.value = '读取工作表、列名与数据类型…'; const session = props.sessionId
  try { const { data } = await executeFileTool(session, 'inspectTable', { fileId: tableFile.value, sheet: sheet.value }); if (session !== props.sessionId) return; profile.value = data.data; sheet.value = data.data.sheet; valueColumn.value = data.data.columns.find((c: any) => c.numeric > 0)?.name || ''; groupBy.value = []; dateColumn.value = '' }
  catch (e) { profile.value = null; error.value = message(e) } finally { busy.value = false }
}
async function execute(name: string, args: unknown, status: string) {
  if (!props.sessionId) return
  const session = props.sessionId; busy.value = true; error.value = ''; result.value = null; progress.value = status
  try { const { data } = await executeFileTool(session, name, args); if (session !== props.sessionId) return; result.value = data.data; await load(); ElMessage.success(data.data.summary || '处理完成') }
  catch (e) { error.value = message(e) } finally { busy.value = false }
}
function analyze() {
  const plan: Record<string, unknown> = { sheet: sheet.value, groupBy: groupBy.value, aggregations: [{ column: aggregate.value === 'count' && !valueColumn.value ? '*' : valueColumn.value, op: aggregate.value, alias: aggregateLabels[aggregate.value] }], chartType: chartType.value, deduplicate: deduplicate.value }
  if (dateColumn.value) plan.dateGroup = { column: dateColumn.value, unit: 'month' }
  void execute('analyzeTable', { fileId: tableFile.value, plan, title: '表格分析' }, '计算统计结果 → 绘制图表 → 生成 Excel…')
}
function makeReport() { void execute('generateDocument', { title: title.value.trim(), markdown: markdown.value, formats: formats.value, figureIds: includeChart.value ? assets.value.filter(a => a.kind === 'chart').slice(-1).map(a => a.id) : [] }, '排版正文与图表 → 生成 Word / PDF…') }
function processPdf() { void execute('pdfTools', { operation: operation.value, fileIds: selectedPdfs.value.map(a => a.id), pages: pages.value, rotation: rotation.value }, '读取选定页面 → 处理 PDF → 保存成果…') }
function ask(prompt: string) { emit('update:visible', false); emit('ask', prompt) }
function askTable() { const names = tableFile.value ? uploads.value.filter(a => a.id === tableFile.value).map(a => a.name) : uploads.value.filter(isTable).map(a => a.name); ask(`请分析当前对话中的表格文件 ${names.join('、')}，先确认实际列名和数据类型，再选择有意义的分组与数值统计，指出缺失值和异常数据，生成可下载的 Excel 和图表，并解释结果。`) }
function extract() { const fields = fieldsText.value.split(/[,，、\n]/).map(s => s.trim()).filter(Boolean); if (fields.length > 12) { ElMessage.error('最多提取 12 个字段'); return } ask(`请从这些文档提取字段并汇总为 Excel：${selectedDocs.value.map(a => a.name).join('、')}；字段：${fields.join('、')}。请阅读原文并按需继续翻页，每个值保留真实位置和包含该值的原文短句。核验后生成下载文件，未核实的值留空，不能把未读内容说成不存在。`) }
</script>
<style scoped>
.chat-task-entry { display: flex; gap: 8px; margin-top: 12px; }
.chat-task-entry .el-input { min-width: 0; }
.manual-toggle { display: flex; align-items: center; justify-content: space-between; width: 100%; margin: 20px 0 12px; padding: 10px 0; border: 0; border-top: 1px solid var(--border); background: transparent; color: var(--text-primary); font-size: 12px; cursor: pointer; }
.workspace-tools { color: var(--text-primary); }.tools-intro { color: var(--text-secondary); font-size: 13px; line-height: 1.7; margin: 0 0 18px; }.tool-upload { display: grid; gap: 7px; justify-items: center; padding: 22px 14px; background: var(--bg-subtle); border: 1px dashed var(--border); border-radius: 14px; cursor: pointer; }.tool-upload:hover { background: var(--bg-selected); border-color: var(--border); }.tool-upload .el-icon { font-size: 25px; }.tool-upload strong { font-size: 14px; }.tool-upload span { color: var(--text-secondary); font-size: 11px; }.disabled { cursor: wait; opacity: .7; }
.tool-files { margin: 18px 0; }.files-label { font-size: 13px; color: var(--text-primary); display: flex; justify-content: space-between; }.tool-file-row { display: flex; gap: 9px; align-items: center; padding: 7px 0; border-bottom: 1px solid var(--border); }.file-name { flex: 1; min-width: 0; font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.file-size { color: var(--text-secondary); font-size: 11px; flex-shrink: 0; }.file-remove { background: var(--bg-selected); color: var(--text-primary); border: 1px solid var(--border); border-radius: 6px; padding: 4px 7px; cursor: pointer; }
.tool-form { display: flex; flex-direction: column; gap: 12px; }.tool-description { color: var(--text-primary); font-size: 13px; line-height: 1.6; margin: 0; }.tool-form label { font-size: 12px; color: var(--text-primary); }.tool-note { color: var(--text-secondary); font-size: 12px; line-height: 1.7; margin: 0; }.form-pair { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }.form-pair > div { display: grid; gap: 9px; }.table-profile { display: flex; align-items: center; gap: 12px; font-size: 12px; color: var(--text-primary); }.table-profile .el-select { width: 150px; margin-left: auto; }.tool-form .el-button { margin-left: 0; }.tool-form .el-checkbox { white-space: normal; }.text-action { background: none; color: var(--text-primary); border: 0; cursor: pointer; text-align: left; text-decoration: underline; font-size: 12px; }.tool-progress { display: flex; gap: 9px; padding: 15px 0; font-size: 13px; color: var(--text-primary); }.tool-error { padding: 13px; border: 1px solid var(--border); background: var(--bg-selected); color: var(--text-primary); border-radius: 9px; font-size: 13px; }.tool-result { margin-top: 24px; display: grid; gap: 12px; border-top: 1px solid var(--border); padding-top: 18px; }.tool-result > span { color: var(--text-primary); font-size: 12px; }.recent-artifacts { margin-top: 24px; border-top: 1px solid var(--border); padding-top: 12px; }.recent-artifacts h3 { font-size: 14px; }.pdf-order { display: grid; gap: 6px; }.pdf-order > div { display: flex; gap: 12px; align-items: center; padding: 7px 10px; background: var(--bg-subtle); border-radius: 7px; font-size: 12px; }.pdf-order span { flex: 1; overflow-wrap: anywhere; }.pdf-order button { background: var(--bg-selected); border: 1px solid var(--border); border-radius: 5px; color: var(--text-primary); padding: 3px 6px; cursor: pointer; }
.workspace-tools button:disabled { opacity: .5; cursor: not-allowed; }.workspace-tools button:focus-visible, .tool-upload:focus-within { outline: 2px solid #fff; outline-offset: 2px; }
@media(max-width: 540px) { .form-pair { grid-template-columns: 1fr; }.tool-upload span { text-align: center; }.file-size { display: none; } }
.tool-upload { display: flex; align-items: center; gap: 12px; justify-content: flex-start; padding: 14px; border-radius: 10px; border-color: var(--border); }
.tool-upload > div { display: grid; gap: 4px; }
.tool-upload span { text-align: left; }
</style>
