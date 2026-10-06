<template>
  <div class="settings-page">
    <h2 class="page-title">品牌设置</h2>
    <p class="page-desc">修改平台名称、LOGO、浏览器图标等前端显示配置，保存后即时生效。</p>

    <div class="settings-grid">
      <div class="setting-card">
        <div class="card-header">
          <h3>基本信息</h3>
        </div>
        <el-form :model="form" label-width="100px" label-position="top">
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="平台名称">
                <el-input v-model="form.platformName" placeholder="Observatory" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="侧边栏标题">
                <el-input v-model="form.sidebarTitle" placeholder="AI 助手" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item label="平台副标题">
            <el-input v-model="form.platformSubtitle" placeholder="企业级 AI 助手平台" />
          </el-form-item>
        </el-form>
      </div>

      <div class="setting-card">
        <div class="card-header">
          <h3>外观</h3>
        </div>
        <el-form :model="form" label-width="100px" label-position="top">
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="强调色">
                <el-select v-model="form.primaryColor" aria-label="黑白主题强调色">
                  <el-option v-for="accent in monochromeAccents" :key="accent.value" :label="accent.label" :value="accent.value" />
                </el-select>
                <div class="form-hint">黑白主题，保持文字与按钮清晰</div>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="浏览器图标 (Favicon)">
                <el-input v-model="form.favicon" placeholder="/vite.svg" />
                <div class="form-hint">支持 URL 或本地路径</div>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="页面 Logo">
                <el-input v-model="form.logoUrl" placeholder="/logo.png（留空使用默认）" />
                <div class="form-hint">PNG/SVG，替换所有页面的顶部图标</div>
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>
      </div>

      <div class="setting-card">
        <div class="card-header">
          <h3>实时预览</h3>
        </div>
        <div class="preview-area">
          <div class="preview-sidebar">
            <div class="preview-logo">
              <svg viewBox="0 0 32 32" fill="none" width="24" height="24">
                <circle cx="16" cy="16" r="14" stroke="#fff" stroke-width="1.2" opacity="0.35"/>
                <circle cx="16" cy="16" r="8" stroke="#fff" stroke-width="1.2" opacity="0.65"/>
                <circle cx="16" cy="10" r="2.5" fill="#fff"/>
                <line x1="16" y1="12.5" x2="16" y2="24" stroke="#fff" stroke-width="1.2" stroke-linecap="round"/>
              </svg>
              <span>{{ form.sidebarTitle || 'AI 助手' }}</span>
            </div>
            <div class="preview-content">
              <span class="preview-platform">{{ form.platformName || 'Observatory' }}</span>
              <span class="preview-sub">{{ form.platformSubtitle || '企业级 AI 助手平台' }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="settings-actions">
      <el-button type="primary" :loading="saving" @click="handleSave">保存设置</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { useBrandingStore, monochromeAccents } from '@/stores/branding'
import { ElMessage } from 'element-plus'

const branding = useBrandingStore()
const saving = ref(false)

const form = reactive({
  platformName: '',
  platformSubtitle: '',
  sidebarTitle: '',
  favicon: '',
  logoUrl: '',
  primaryColor: '',
})

onMounted(async () => {
  await branding.fetchBranding()
  Object.assign(form, branding.config)
})

async function handleSave() {
  saving.value = true
  try {
    await branding.saveBranding(form)
    ElMessage.success('品牌设置已保存')
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.settings-page { padding: 24px; max-width: 800px; }
.page-title { font-family: var(--font-display); font-size: 1.5rem; font-weight: 400; margin-bottom: 6px; }
.page-desc { font-size: 13px; color: var(--twilight); margin-bottom: 28px; }

.settings-grid { display: flex; flex-direction: column; gap: 18px; margin-bottom: 28px; }
.setting-card { background: var(--bg-card); border: 1px solid var(--horizon-soft); border-radius: var(--radius); padding: 24px; }
.card-header h3 { font-family: var(--font-display); font-size: 1rem; font-weight: 400; margin-bottom: 16px; }

.form-hint { font-size: 11px; color: var(--twilight); margin-top: 4px; }

.preview-area { padding: 20px; background: var(--bg-subtle); border-radius: var(--radius); }
.preview-sidebar { background: var(--void); border-radius: var(--radius-sm); padding: 16px; color: var(--starlight); display: flex; align-items: center; gap: 16px; }
.preview-logo { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.preview-logo span { font-family: var(--font-body); font-size: 14px; font-weight: 600; }
.preview-content { display: flex; flex-direction: column; }
.preview-platform { font-size: 13px; font-weight: 500; }
.preview-sub { font-size: 11px; color: var(--twilight); margin-top: 2px; }

.settings-actions { display: flex; gap: 12px; }
@media (max-width: 700px) {
  .settings-page { padding: 0; }
  .setting-card { padding: 18px; }
  .setting-card :deep(.el-col) { flex-basis: 100%; max-width: 100%; }
  .preview-area { padding: 10px; }
  .preview-sidebar { flex-wrap: wrap; }
}
</style>
