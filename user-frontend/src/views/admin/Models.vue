<template>
  <div class="models-page">
    <div class="page-header">
      <h2>AI 模型配置</h2>
      <el-button type="primary" :icon="Plus" @click="openAddDialog">添加模型</el-button>
    </div>

    <el-table :data="models" v-loading="loading" stripe>
      <el-table-column prop="name" label="名称" width="150" />
      <el-table-column prop="provider" label="提供商" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="getProviderType(row.provider)">{{ row.provider }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="modelName" label="模型标识" width="160">
        <template #default="{ row }">
          <code class="model-code">{{ row.modelName }}</code>
        </template>
      </el-table-column>
      <el-table-column prop="baseUrl" label="Base URL" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="url-text">{{ row.baseUrl || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="密钥" width="80" align="center">
        <template #default="{ row }">
          <span class="key-dot" :class="{ set: row.apiKeyConfigured }"></span>
          <span class="key-label">{{ row.apiKeyConfigured ? '已配置' : '未配置' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="80" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.embeddingDimension > 0" type="success" size="small">嵌入</el-tag>
          <el-tag v-else type="primary" size="small">对话</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="temperature" label="温度" width="70" align="center" />
      <el-table-column prop="maxTokens" label="最大Token" width="100" align="right" />
      <el-table-column label="状态" width="120" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled"
            @change="(val: boolean) => toggleModel(row, val)"
            size="small"
            :loading="row._toggling"
          />
          <el-tag v-if="row.isDefault" type="warning" size="small" style="margin-left:6px">默认</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="100" align="center">
        <template #default="{ row }">
          <el-dropdown trigger="click" @command="(cmd: string) => handleCommand(cmd, row)">
            <button class="action-trigger" title="">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="5" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="12" cy="19" r="1"/></svg>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="edit">编辑</el-dropdown-item>
                <el-dropdown-item command="default" :disabled="row.isDefault">{{ row.isDefault ? '已是默认' : '设为默认' }}</el-dropdown-item>
                <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </el-table-column>
    </el-table>

    <!-- Add/Edit Dialog -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑模型' : '添加模型'" width="600px" top="5vh">
      <el-form :model="form" label-width="100px" :rules="rules" ref="formRef">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="名称" prop="name">
              <el-input v-model="form.name" placeholder="DeepSeek Chat" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="提供商" prop="provider">
              <el-select v-model="form.provider" placeholder="选择提供商" style="width: 100%">
                <el-option label="DeepSeek" value="deepseek" />
                <el-option label="OpenAI" value="openai" />
                <el-option label="Anthropic" value="anthropic" />
                <el-option label="SiliconFlow" value="siliconflow" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="Base URL" prop="baseUrl">
          <el-input v-model="form.baseUrl" placeholder="https://api.deepseek.com/v1" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="模型标识" prop="modelName">
              <el-input v-model="form.modelName" placeholder="deepseek-chat" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="API Key">
              <el-input v-model="form.apiKey" type="password"
                :placeholder="isEdit ? '已配置（留空不变，输入新值替换）' : 'sk-... 或环境变量名'"
                show-password />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="嵌入维度">
              <el-input-number v-model="form.embeddingDimension" :min="0" :max="4096" placeholder="0=对话模型" style="width: 100%" controls-position="right" />
              <div class="form-hint">设为 1024 即为嵌入模型</div>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="温度" prop="temperature">
              <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" show-stops :marks="tempMarks" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="最大 Token" prop="maxTokens">
              <el-input-number v-model="form.maxTokens" :min="100" :max="200000" :step="100" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button v-if="isEdit" @click="testConnection" :loading="testing" :disabled="!form.id">测试连接</el-button>
        <div style="flex:1"></div>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitModel" :loading="submitting">{{ isEdit ? '保存' : '添加' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { getModels, createModel, setDefaultModel, updateModel, deleteModel as apiDeleteModel, testModelConnection } from '@/api'
import type { AiModel } from '@/api'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'

interface ModelRow extends AiModel {
  _toggling?: boolean
  _settingDefault?: boolean
}

const models = ref<ModelRow[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
const testing = ref(false)
const formRef = ref<FormInstance>()

const tempMarks = { 0: '0', 0.5: '0.5', 1: '1', 1.5: '1.5', 2: '2' }

const defaultForm = () => ({
  id: '',
  name: '',
  provider: 'deepseek',
  baseUrl: 'https://api.deepseek.com/v1',
  modelName: '',
  apiKey: '',
  temperature: 0.3,
  maxTokens: 4096,
  embeddingDimension: 0,
  enabled: true,
})

const form = reactive(defaultForm())

const rules: FormRules = {
  name: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
  provider: [{ required: true, message: '请选择提供商', trigger: 'change' }],
  baseUrl: [{ required: true, message: '请输入 Base URL', trigger: 'blur' }],
  modelName: [{ required: true, message: '请输入模型标识', trigger: 'blur' }],
}

function getProviderType(provider: string) {
  const types: Record<string, string> = { deepseek: 'primary', openai: 'success', anthropic: 'warning', siliconflow: 'info' }
  return types[provider] || 'info'
}

async function loadModels() {
  loading.value = true
  try {
    const { data } = await getModels()
    models.value = (data.data || []).map(m => ({ ...m, _toggling: false, _settingDefault: false }))
  } catch {
    ElMessage.error('加载模型失败')
  } finally {
    loading.value = false
  }
}

async function toggleModel(row: ModelRow, val: boolean) {
  row._toggling = true
  try {
    await updateModel(row.id, { enabled: val })
    row.enabled = val
    ElMessage.success(val ? '已启用' : '已禁用')
  } catch {
    ElMessage.error('操作失败')
  } finally {
    row._toggling = false
  }
}

function openAddDialog() {
  isEdit.value = false
  Object.assign(form, defaultForm())
  dialogVisible.value = true
}

function editModel(model: ModelRow) {
  isEdit.value = true
  Object.assign(form, {
    id: model.id, name: model.name, provider: model.provider,
    baseUrl: model.baseUrl || '', modelName: model.modelName,
    apiKey: model.apiKeyAlias || '',
    temperature: model.temperature, maxTokens: model.maxTokens,
    embeddingDimension: model.embeddingDimension || 0,
    enabled: model.enabled,
  })
  dialogVisible.value = true
}

async function submitModel() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      if (isEdit.value) {
        await updateModel(form.id, {
          name: form.name, modelName: form.modelName,
          temperature: form.temperature, maxTokens: form.maxTokens,
          embeddingDimension: form.embeddingDimension || undefined,
          apiKeyAlias: form.apiKey || undefined,
          enabled: form.enabled,
        })
        ElMessage.success('模型已更新')
      } else {
        await createModel({
          name: form.name, provider: form.provider, baseUrl: form.baseUrl,
          modelName: form.modelName, temperature: form.temperature,
          maxTokens: form.maxTokens,
          embeddingDimension: form.embeddingDimension || undefined,
          apiKeyAlias: form.apiKey || undefined,
        })
        ElMessage.success('模型已添加')
      }
      dialogVisible.value = false
      loadModels()
    } catch (e: any) {
      ElMessage.error(e.response?.data?.message || '操作失败')
    } finally {
      submitting.value = false
    }
  })
}

async function setDefault(row: ModelRow) {
  row._settingDefault = true
  try {
    await setDefaultModel(row.id)
    ElMessage.success(`${row.name} 已设为默认模型`)
    loadModels()
  } catch {
    ElMessage.error('设置失败')
  } finally {
    row._settingDefault = false
  }
}

async function deleteModel(row: ModelRow) {
  try {
    await ElMessageBox.confirm(`确定删除 "${row.name}"？`, '确认删除', { type: 'warning' })
    await apiDeleteModel(row.id)
    ElMessage.success('已删除')
    loadModels()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e.response?.data?.message || '删除失败')
  }
}

async function testConnection() {
  if (!form.id) return
  testing.value = true
  try {
    const { data } = await testModelConnection(form.id)
    const r = data.data
    if (r?.success) {
      ElMessage.success(r.message || '连接成功')
    } else {
      ElMessage.warning(r?.message || '连接失败')
    }
  } catch (e: any) {
    ElMessage.error('测试失败: ' + (e.response?.data?.message || e.message))
  } finally {
    testing.value = false
  }
}

function handleCommand(cmd: string, row: ModelRow) {
  if (cmd === 'edit') editModel(row)
  else if (cmd === 'default') setDefault(row)
  else if (cmd === 'delete') deleteModel(row)
}

onMounted(loadModels)
</script>

<style scoped>
.models-page { padding: 24px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.page-header h2 { font-size: 20px; font-weight: 600; margin: 0; }
.model-code { background: var(--bg-subtle); padding: 2px 8px; border-radius: 4px; font-size: 12px; color: var(--pulsar); }
.url-text { font-size: 12px; color: var(--twilight); }

.key-dot {
  display: inline-block; width: 7px; height: 7px; border-radius: 50%;
  background: var(--flare); margin-right: 5px; vertical-align: middle;
}
.key-dot.set { background: var(--aurora); }
.key-label { font-size: 12px; color: var(--twilight); vertical-align: middle; }

.action-trigger {
  display: inline-flex; align-items: center; justify-content: center;
  width: 32px; height: 32px; border-radius: 50%;
  background: transparent; border: none; cursor: pointer;
  color: var(--twilight); transition: all var(--duration-fast) var(--ease-out);
}
.action-trigger:hover { background: var(--bg-subtle); color: var(--pulsar); }
.action-trigger:active { transform: scale(0.92); }

.form-hint { font-size: 11px; color: var(--twilight); margin-top: 4px; }
</style>
