<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="visible" class="auth-overlay" @click.self="handleClose">
        <div class="auth-dialog">
          <button class="auth-close" @click="handleClose" title="关闭">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>

          <div class="auth-brand">
            <AppLogo :size="32" />
            <h2>{{ isRegister ? '创建账号' : '登录' }}</h2>
          </div>

          <!-- Login Form -->
          <form v-if="!isRegister" @submit.prevent="handleLogin" class="auth-form">
            <div class="field">
              <input v-model="loginForm.username" type="text" placeholder="用户名" autocomplete="username" required />
            </div>
            <div class="field">
              <input v-model="loginForm.password" type="password" placeholder="密码" autocomplete="current-password" required @keyup.enter="handleLogin" />
            </div>
            <button type="submit" class="submit-btn" :disabled="loading">
              {{ loading ? '登录中…' : '登 录' }}
            </button>
            <p class="form-err" v-if="error">{{ error }}</p>
          </form>

          <!-- Register Form -->
          <form v-else @submit.prevent="handleRegister" class="auth-form">
            <div class="field">
              <input v-model="regForm.username" type="text" placeholder="用户名" autocomplete="username" required />
            </div>
            <div class="field">
              <input v-model="regForm.displayName" type="text" placeholder="显示名称（可选）" />
            </div>
            <div class="field">
              <input v-model="regForm.email" type="email" placeholder="邮箱（可选）" autocomplete="email" />
            </div>
            <div class="field">
              <input v-model="regForm.password" type="password" placeholder="密码" autocomplete="new-password" required />
            </div>
            <button type="submit" class="submit-btn" :disabled="loading">
              {{ loading ? '注册中…' : '注 册' }}
            </button>
            <p class="form-err" v-if="error">{{ error }}</p>
          </form>

          <div class="auth-switch">
            <button @click="toggleMode" class="switch-btn">
              {{ isRegister ? '已有账号？去登录' : '没有账号？去注册' }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useBrandingStore } from '@/stores/branding'
import AppLogo from '@/components/AppLogo.vue'
import { register } from '@/api'
import { ElMessage } from 'element-plus'
const branding = useBrandingStore()

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ 'update:visible': [v: boolean]; 'logged-in': [] }>()

const auth = useAuthStore()
const isRegister = ref(false)
const loading = ref(false)
const error = ref('')
const loginForm = reactive({ username: '', password: '' })
const regForm = reactive({ username: '', displayName: '', email: '', password: '' })

watch(() => props.visible, (v) => {
  if (v) { error.value = ''; loading.value = false }
})

function toggleMode() {
  isRegister.value = !isRegister.value
  error.value = ''
}

async function handleLogin() {
  error.value = ''
  if (!loginForm.username.trim() || !loginForm.password.trim()) {
    error.value = '请填写用户名和密码'
    return
  }
  loading.value = true
  try {
    await auth.login(loginForm.username.trim(), loginForm.password)
    ElMessage.success('欢迎回来')
    emit('update:visible', false)
    emit('logged-in')
  } catch (e: any) {
    error.value = e.response?.data?.message || '登录失败'
  } finally {
    loading.value = false
  }
}

async function handleRegister() {
  error.value = ''
  if (!regForm.username.trim() || !regForm.password.trim()) {
    error.value = '请填写用户名和密码'
    return
  }
  loading.value = true
  try {
    const { data } = await register({ username: regForm.username.trim(), password: regForm.password, email: regForm.email.trim(), displayName: regForm.displayName.trim() })
    await auth.login(regForm.username.trim(), regForm.password)
    ElMessage.success('注册成功')
    emit('update:visible', false)
    emit('logged-in')
  } catch (e: any) {
    error.value = e.response?.data?.message || '注册失败'
  } finally {
    loading.value = false
  }
}

function handleClose() {
  emit('update:visible', false)
}
</script>

<style scoped>
.auth-overlay {
  position: fixed; inset: 0; z-index: 1000;
  display: flex; align-items: center; justify-content: center;
  background: rgba(17,17,17, 0.7);
  backdrop-filter: blur(8px);
}
.auth-dialog {
  position: relative;
  width: 400px; max-width: 90vw;
  background: var(--abyss);
  border: 1px solid var(--horizon);
  border-radius: var(--radius-xl);
  padding: 40px 36px 32px;
  box-shadow: 0 0 60px rgba(0,0,0,0.3);
}
.auth-close {
  position: absolute; top: 14px; right: 14px;
  width: 30px; height: 30px; border-radius: 50%;
  background: transparent; border: none; cursor: pointer;
  color: var(--twilight); display: flex; align-items: center; justify-content: center;
  transition: all var(--duration-fast) var(--ease-out);
}
.auth-close:hover { background: rgba(255,255,255,0.08); color: var(--starlight); }

.auth-brand { text-align: center; margin-bottom: 28px; color: var(--starlight); }
.auth-brand h2 { font-family: var(--font-display); font-size: 1.4rem; font-weight: 400; margin-top: 10px; }

.auth-form { display: flex; flex-direction: column; gap: 12px; }
.field input {
  width: 100%; padding: 11px 14px; border-radius: var(--radius-sm);
  background: rgba(255,255,255,0.04); border: 1px solid var(--control-border);
  color: var(--starlight); font-family: var(--font-body); font-size: 14px;
  outline: none; transition: all var(--duration-fast) var(--ease-out);
}
.field input::placeholder { color: var(--twilight); }
.field input:focus { border-color: var(--pulsar); box-shadow: 0 0 0 3px var(--pulsar-glow); }

.submit-btn {
  width: 100%; padding: 11px; margin-top: 4px;
  border-radius: var(--radius-sm); border: none;
  background: var(--pulsar); color: var(--on-accent);
  font-family: var(--font-body); font-size: 14px; font-weight: 500;
  cursor: pointer; transition: all var(--duration-fast) var(--ease-out);
}
.submit-btn:hover { background: var(--pulsar-deep); box-shadow: var(--shadow-glow); }
.submit-btn:disabled { opacity: 0.6; cursor: not-allowed; }

.form-err { color: var(--flare); font-size: 12px; text-align: center; }

.auth-switch { text-align: center; margin-top: 20px; }
.switch-btn {
  background: none; border: none; color: var(--twilight); font-size: 12px;
  cursor: pointer; font-family: var(--font-body); transition: color var(--duration-fast);
}
.switch-btn:hover { color: var(--starlight); }

/* Transition */
.modal-enter-active { transition: all 0.3s var(--ease-out); }
.modal-leave-active { transition: all 0.2s var(--ease-out); }
.modal-enter-from, .modal-leave-to { opacity: 0; }
.modal-enter-from .auth-dialog { transform: scale(0.95) translateY(10px); }
.modal-leave-to .auth-dialog { transform: scale(0.95) translateY(10px); }
</style>
