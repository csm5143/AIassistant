<template>
  <div class="login-page">
    <div class="login-bg">
      <!-- Subtle grid texture -->
      <div class="bg-grid"></div>
      <!-- Ambient glow spheres -->
      <div class="bg-glow glow-1"></div>
      <div class="bg-glow glow-2"></div>
    </div>

    <div class="login-card">
      <div class="login-brand">
        <div class="brand-mark">
          <svg viewBox="0 0 48 48" fill="none" width="42" height="42">
            <circle cx="24" cy="24" r="22" stroke="currentColor" stroke-width="1.5" opacity="0.3"/>
            <circle cx="24" cy="24" r="12" stroke="currentColor" stroke-width="1.5" opacity="0.6"/>
            <circle cx="24" cy="16" r="4" fill="currentColor"/>
            <line x1="24" y1="20" x2="24" y2="36" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            <line x1="18" y1="30" x2="30" y2="30" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" opacity="0.6"/>
          </svg>
        </div>
        <h1>Observatory</h1>
        <p>企业级 AI 智能助手平台</p>
      </div>

      <el-form :model="form" :rules="rules" ref="formRef" @submit.prevent="handleLogin" class="login-form">
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            placeholder="用户名"
            size="large"
            :prefix-icon="User"
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            size="large"
            :prefix-icon="Lock"
            show-password
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            size="large"
            :loading="loading"
            @click="handleLogin"
            class="login-btn"
          >
            <span v-if="!loading">进入平台</span>
            <span v-else>验证中…</span>
          </el-button>
        </el-form-item>
      </el-form>

      <div class="login-footer">
        <p>演示账号 <code>admin</code> / <code>admin123</code></p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { User, Lock } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const formRef = ref()

const form = reactive({ username: 'admin', password: 'admin123' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function handleLogin() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    ElMessage.success('欢迎回来')
    router.push(auth.isAdmin ? '/admin/dashboard' : '/chat')
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
}

/* ---------- Background ---------- */
.login-bg {
  position: absolute; inset: 0;
  background: var(--void);
}
.bg-grid {
  position: absolute; inset: 0;
  background-image:
    linear-gradient(rgba(255,255,255,0.015) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255,255,255,0.015) 1px, transparent 1px);
  background-size: 60px 60px;
}
.bg-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(120px);
  opacity: 0.12;
}
.glow-1 {
  width: 600px; height: 600px;
  background: var(--pulsar);
  top: -20%; right: -15%;
  animation: drift 24s infinite ease-in-out;
}
.glow-2 {
  width: 400px; height: 400px;
  background: var(--aurora);
  bottom: -15%; left: -10%;
  animation: drift 20s infinite ease-in-out reverse;
}
@keyframes drift {
  0%, 100% { transform: translate(0, 0); }
  33% { transform: translate(30px, -20px); }
  66% { transform: translate(-20px, 30px); }
}

/* ---------- Card ---------- */
.login-card {
  position: relative; z-index: 1;
  width: 400px;
  padding: 52px 44px 40px;
  background: var(--abyss);
  border: 1px solid var(--horizon);
  border-radius: var(--radius-xl);
  box-shadow: 0 0 60px rgba(128,128,128, 0.06);
}

/* ---------- Brand ---------- */
.login-brand {
  text-align: center;
  margin-bottom: 36px;
}
.brand-mark {
  color: var(--starlight);
  display: inline-flex;
  margin-bottom: 18px;
  opacity: 0.85;
}
.login-brand h1 {
  font-family: var(--font-display);
  font-size: 2rem;
  font-weight: 400;
  color: var(--starlight);
  margin-bottom: 6px;
  letter-spacing: 0.02em;
}
.login-brand p {
  font-size: 13px;
  color: var(--twilight);
  letter-spacing: 0.04em;
}

/* ---------- Form ---------- */
.login-form :deep(.el-input__wrapper) {
  background: rgba(255,255,255,0.04);
  border: 1px solid var(--horizon);
  box-shadow: none;
}
.login-form :deep(.el-input__wrapper:hover) {
  border-color: rgba(255,255,255,0.2);
}
.login-form :deep(.el-input__wrapper.is-focus) {
  border-color: var(--pulsar);
  box-shadow: 0 0 0 3px var(--pulsar-glow);
}
.login-form :deep(.el-input__inner) {
  color: var(--starlight);
}
.login-form :deep(.el-input__inner::placeholder) {
  color: var(--twilight);
}
.login-form :deep(.el-input__prefix .el-icon) {
  color: var(--twilight);
}

.login-btn {
  width: 100%;
  height: 48px;
  font-family: var(--font-body);
  font-size: 15px;
  font-weight: 500;
  letter-spacing: 0.06em;
  margin-top: 8px;
}

/* ---------- Footer ---------- */
.login-footer {
  text-align: center;
  margin-top: 28px;
}
.login-footer p {
  font-size: 12px;
  color: var(--twilight);
  opacity: 1;
}
.login-footer code {
  font-family: var(--font-mono);
  background: rgba(255,255,255,0.05);
  padding: 2px 6px;
  border-radius: 3px;
  font-size: 11px;
}
</style>
