<template>
  <div class="config-page">
    <header class="page-header"><div><h1>API 配置</h1><p class="page-desc">日常对话与知识库问答，配置这两项即可。</p></div><el-button @click="openCreate('chat')">添加配置</el-button></header>
    <div v-if="loading" class="config-loading">加载中…</div>
    <template v-else>
      <div class="primary-grid">
        <template v-for="(type, index) in basicTypes" :key="type.value">
          <ModelConfigurationCard v-if="primary[index]" :model="primary[index]!" :label="type.label" :testing="testing[primary[index]!.id]" :busy="changing[primary[index]!.id]" @edit="openEdit" @test="testModel" @toggle="toggleModel" @delete="deleteModel" />
          <article v-else class="empty-card"><h2>{{ type.label }}</h2><p>{{ type.description }}</p><el-button type="primary" plain @click="openCreate(type.value)">配置{{ type.label }}</el-button></article>
        </template>
      </div>
      <div class="reuse-notes"><p v-if="visionShared">图片识别复用当前对话 API，无需另外配置。</p><p>PDF 本地文字识别无需云端 OCR 密钥；知识库重排复用嵌入服务密钥。</p></div>
      <section class="optional-section">
        <button class="optional-toggle" type="button" :aria-expanded="optionalOpen" aria-controls="optional-models" @click="optionalOpen = !optionalOpen"><span>{{ optionalOpen ? '▾' : '▸' }} 可选配置 <small>{{ optional.length }} 项 · {{ optional.filter(m => m.enabled).length }} 项启用</small></span><span>{{ optionalOpen ? '收起' : '展开' }}</span></button>
        <div v-if="optionalOpen" id="optional-models" class="optional-content"><p class="page-desc">备用对话模型、独立识图和云端 OCR 按需启用。停用后仍可保留配置。</p>
          <div class="optional-toolbar"><el-button size="small" @click="openCreate('vision')">添加可选配置</el-button><el-switch v-model="showInactive" aria-label="显示已停用配置" active-text="显示已停用" /></div>
          <div v-if="visibleOptional.length" class="optional-grid"><ModelConfigurationCard v-for="row in visibleOptional" :key="row.id" :model="row" :label="typeLabel(row.type)" :testing="testing[row.id]" :busy="changing[row.id]" @edit="openEdit" @test="testModel" @toggle="toggleModel" @delete="deleteModel" /></div>
          <p v-else class="empty-optional">暂无启用的可选配置</p>
        </div>
      </section>
    </template>
    <el-dialog v-model="dialogVisible" :title="isCreate ? '添加配置' : '编辑配置'" width="520px" class="api-dialog" :close-on-click-modal="false">
      <el-form :model="form" label-position="top" @submit.prevent="saveModel">
        <el-form-item v-if="isCreate" label="用途"><el-select :model-value="form.type" style="width:100%" @change="changeType"><el-option v-for="type in configurationTypes" :key="type.value" :label="type.label" :value="type.value" /></el-select></el-form-item>
        <el-form-item label="模型名称"><el-input v-model="form.modelName" placeholder="服务商提供的模型名称" /></el-form-item>
        <el-form-item label="API 地址"><el-input v-model="form.baseUrl" placeholder="https://…/v1" /></el-form-item>
        <el-form-item label="API Key"><el-input v-model="form.apiKeyAlias" type="password" show-password autocomplete="new-password" :placeholder="isCreate ? '填写密钥或已有环境变量名称' : '留空保留原密钥'" /><span class="form-hint">{{ isCreate ? '密钥加密保存。' : '只在需要更换密钥时填写。' }}</span></el-form-item>
        <p v-if="form.type === 'embedding'" class="form-note">修改后需要重启后端；更换模型或维度需要重新索引现有文档。重排服务使用部署环境中的嵌入密钥，请同步更新。</p>
        <p v-if="form.type === 'ocr'" class="form-note">百度 OCR 的密钥格式：API_KEY:SECRET_KEY（英文冒号）。PDF 本地 OCR 无需此配置。</p>
        <el-collapse v-model="formAdvanced"><el-collapse-item title="高级设置" name="advanced">
          <el-form-item label="显示名称"><el-input v-model="form.name" placeholder="留空使用模型名称" /></el-form-item>
          <el-form-item label="提供商标识"><el-input v-model="form.provider" placeholder="如 deepseek / siliconflow" /></el-form-item>
          <el-row v-if="form.type === 'chat'" :gutter="12"><el-col :span="12"><el-form-item label="温度"><el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" /></el-form-item></el-col><el-col :span="12"><el-form-item label="最大输出 Tokens"><el-input-number v-model="form.maxTokens" :min="1" :max="131072" /></el-form-item></el-col></el-row>
          <el-form-item v-if="form.type === 'embedding'" label="嵌入维度"><el-input-number v-model="form.embeddingDimension" :min="1" :max="65536" /></el-form-item>
          <el-form-item v-if="form.type === 'chat'" label="设为默认对话模型"><el-switch v-model="form.isDefault" /></el-form-item>
          <el-form-item label="优先级（数字越小越优先）"><el-input-number v-model="form.sortOrder" :min="0" :max="999" /></el-form-item>
        </el-collapse-item></el-collapse>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveModel">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="testVisible" title="连接测试" width="420px" class="api-dialog"><div v-if="testResult" class="test-result" :class="testResult.success ? 'success' : 'fail'"><strong>{{ testResult.success ? '连接成功' : '连接失败' }}</strong><p>{{ testResult.message }}</p></div><p class="form-hint">连接测试仅验证服务响应，不代表已完成模型能力测试。</p></el-dialog>
  </div>
</template>
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import api from '@/api'
import { ElMessage } from 'element-plus'
import ModelConfigurationCard from '@/components/ModelConfigurationCard.vue'
import { configurationTypes, configurationPreset, primaryConfigurations, reusesChatVision, type ModelConfiguration } from '@/utils/modelConfiguration'
const models = ref<ModelConfiguration[]>([])
const loading = ref(false)
const basicTypes = configurationTypes.slice(0, 2)
const primary = computed(() => primaryConfigurations(models.value))
const optional = computed(() => models.value.filter(row => !primary.value.some(main => main?.id === row.id)))
const optionalOpen = ref(false)
const showInactive = ref(false)
const visibleOptional = computed(() => optional.value.filter(row => showInactive.value || row.enabled))
const visionShared = computed(() => reusesChatVision(primary.value[0]))
const testing = ref<Record<string, boolean>>({})
const changing = ref<Record<string, boolean>>({})
const dialogVisible = ref(false)
const isCreate = ref(false)
const saving = ref(false)
const formAdvanced = ref<string[]>([])
const form = ref<ModelConfiguration>({ ...configurationPreset('chat'), id: '' })
const testVisible = ref(false)
const testResult = ref<{ success: boolean; message: string } | null>(null)
function typeLabel(type: string) { return type === 'chat' ? '备用对话模型' : type === 'cleaning' ? '旧版清洗配置（未接入）' : configurationTypes.find(t => t.value === type)?.label || type }
onMounted(fetchModels)
async function fetchModels() {
  loading.value = true
  try { const { data } = await api.get('/models/admin'); models.value = data.data || [] }
  catch { ElMessage.error('配置加载失败，请重试') }
  finally { loading.value = false }
}
function openCreate(type: string) {
  isCreate.value = true
  form.value = { ...configurationPreset(type), id: '', isDefault: type === 'chat' && !primary.value[0] }
  formAdvanced.value = []; dialogVisible.value = true
}
function changeType(type: string) {
  // Preserve a newly entered key while switching purpose; existing models keep their type.
  form.value = { ...configurationPreset(type), id: '', apiKeyAlias: form.value.apiKeyAlias, isDefault: type === 'chat' && !primary.value[0] }
}
function openEdit(row: ModelConfiguration) {
  isCreate.value = false; form.value = { ...row, apiKeyAlias: '' }
  formAdvanced.value = []; dialogVisible.value = true
}
async function saveModel() {
  const modelName = form.value.modelName.trim()
  const baseUrl = form.value.baseUrl.trim()
  try { if (!['https:', 'http:'].includes(new URL(baseUrl).protocol)) throw new Error() }
  catch { ElMessage.warning('请填写有效的 API 地址'); return }
  if (!modelName || (isCreate.value && !form.value.apiKeyAlias?.trim())) { ElMessage.warning('请填写模型名称和 API Key'); return }
  saving.value = true
  try {
    const { apiKeyConfigured: _status, id, ...values } = form.value
    const payload = { ...values, name: form.value.name.trim() || modelName, modelName, baseUrl, apiKeyAlias: form.value.apiKeyAlias?.trim() || undefined }
    if (isCreate.value) await api.post('/models', payload)
    else await api.put(`/models/${id}`, payload)
    ElMessage.success(form.value.type === 'embedding' ? '已保存，重启后端后生效' : '已保存')
    dialogVisible.value = false; await fetchModels()
  } catch (e: any) { ElMessage.error(e.response?.data?.message || '保存失败') }
  finally { saving.value = false }
}
async function testModel(row: ModelConfiguration) {
  testing.value[row.id] = true
  try { const { data } = await api.post(`/models/${row.id}/test`); testResult.value = data.data; testVisible.value = true }
  catch (e: any) { testResult.value = { success: false, message: e.response?.data?.message || '测试失败' }; testVisible.value = true }
  finally { testing.value[row.id] = false }
}
function isLastRequired(row: ModelConfiguration) {
  return row.enabled && ['chat', 'embedding'].includes(row.type) && models.value.filter(m => m.enabled && m.type === row.type).length === 1
}
async function toggleModel(row: ModelConfiguration) {
  if (isLastRequired(row)) { ElMessage.warning('请先配置并启用另一项同用途 API'); return }
  changing.value[row.id] = true
  try { await api.put(`/models/${row.id}`, { enabled: !row.enabled }); await fetchModels() }
  catch { ElMessage.error('操作失败') }
  finally { changing.value[row.id] = false }
}
async function deleteModel(row: ModelConfiguration) {
  if (isLastRequired(row)) { ElMessage.warning('请先配置并启用另一项同用途 API'); return }
  try { await api.delete(`/models/${row.id}`); ElMessage.success('已删除'); await fetchModels() }
  catch { ElMessage.error('删除失败') }
}
</script>
<style scoped>
.config-page { padding: 4px 0; max-width: 1080px; }
.page-header { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 24px; }
.page-header h1 { font-size: 22px; font-weight: 600; margin: 0 0 7px; }
.page-desc { font-size: 13px; line-height: 1.7; color: var(--twilight); margin: 0; }
.primary-grid, .optional-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.empty-card { border: 1px dashed var(--horizon-soft); border-radius: 14px; padding: 24px; background: var(--bg-card); }
.empty-card h2 { font-size: 16px; } .empty-card p, .empty-optional { color: var(--twilight); font-size: 13px; }
.reuse-notes { margin: 18px 0 28px; padding: 0 2px; color: var(--twilight); font-size: 12px; line-height: 1.7; }
.reuse-notes p { margin: 4px 0; }
.optional-section { border-top: 1px solid var(--horizon-soft); }
.optional-toggle { display: flex; align-items: center; justify-content: space-between; gap: 12px; width: 100%; border: 0; background: transparent; padding: 18px 0; color: var(--text-primary); font: inherit; font-size: 14px; cursor: pointer; }
.optional-toggle small { font-size: 12px; font-weight: 400; color: var(--twilight); margin-left: 12px; }
.optional-toggle > span:last-child { color: var(--twilight); font-size: 12px; }
.optional-content { padding-bottom: 24px; } .optional-toolbar { display: flex; align-items: center; justify-content: space-between; margin: 14px 0; gap: 12px; }
.config-loading { text-align: center; padding: 60px 0; color: var(--twilight); }
.form-hint { display: block; color: var(--twilight); font-size: 12px; line-height: 1.6; margin-top: 5px; }
.form-note { color: var(--twilight); font-size: 12px; line-height: 1.7; background: var(--bg-subtle); border-radius: 8px; padding: 10px 12px; margin-bottom: 16px; }
.test-result { padding: 16px; border-radius: 8px; overflow-wrap: anywhere; } .test-result p { margin: 10px 0 0; font-size: 13px; }
.test-result.success { background: var(--bg-subtle); color: var(--text-primary); border: 1px solid var(--control-border); }
.test-result.fail { background: var(--bg-subtle); color: var(--text-primary); border: 1px dashed #aaaaaa; }
:global(.api-dialog.el-dialog) { max-width: calc(100vw - 24px); } :deep(.el-input-number) { max-width: 100%; }
@media (max-width: 760px) { .primary-grid, .optional-grid { grid-template-columns: 1fr; } .page-header { align-items: flex-start; } .optional-toggle small { display: block; margin: 5px 0 0 15px; } }
</style>
