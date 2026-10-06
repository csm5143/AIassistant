<template>
  <div class="register-page">
    <div class="register-card">
      <div class="brand">
        <svg viewBox="0 0 32 32" fill="none" width="48" height="48">
          <rect width="32" height="32" rx="8" fill="url(#logo-g)"/>
          <path d="M10 16c0-3.3 2.7-6 6-6s6 2.7 6 6-2.7 6-6 6" stroke="#000" stroke-width="2" stroke-linecap="round"/>
          <circle cx="16" cy="16" r="2" fill="#000"/>
          <defs><linearGradient id="logo-g" x1="0" y1="0" x2="32" y2="32"><stop stop-color="#e0e0e0"/><stop offset="1" stop-color="#cccccc"/></linearGradient></defs>
        </svg>
        <h1>AI 助手</h1>
      </div>

      <h2>创建账号</h2>

      <el-form ref="formRef" :model="form" :rules="rules" @submit.prevent="handleRegister" label-position="top">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="3-32个字符" size="large" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" placeholder="至少6个字符" show-password size="large" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" placeholder="再次输入密码" show-password size="large" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" type="email" placeholder="选填" size="large" />
        </el-form-item>
        <el-form-item label="显示名称" prop="displayName">
          <el-input v-model="form.displayName" placeholder="选填，公开显示的名称" size="large" />
        </el-form-item>

        <el-button type="primary" size="large" :loading="loading" native-type="submit" class="submit-btn">
          注册
        </el-button>
      </el-form>

      <div class="login-link">
        已有账号？ <router-link to="/login">立即登录</router-link>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { register } from '@/api'
import { ElMessage } from 'element-plus'

const router = useRouter()
const formRef = ref()
const loading = ref(false)
const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  email: '',
  displayName: '',
})

const validateConfirm = (_rule: any, value: string, callback: any) => {
  if (value !== form.password) {
    callback(new Error('两次密码不一致'))
  } else {
    callback()
  }
}

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名需为3-32个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少6个字符', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' },
  ],
  email: [
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
}

async function handleRegister() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  loading.value = true
  try {
    await register({
      username: form.username,
      password: form.password,
      email: form.email || undefined,
      displayName: form.displayName || undefined,
    })
    ElMessage.success('注册成功！请登录')
    router.push('/login')
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '注册失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.register-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: radial-gradient(circle at 78% 20%, rgba(224,224,224,.12), transparent 34%), var(--bg-main);
  padding: 20px;
}

.register-card {
  background: var(--bg-card);
  border: 1px solid var(--horizon-soft);
  border-radius: 16px;
  padding: 40px;
  width: 100%;
  max-width: 420px;
  box-shadow: 0 20px 60px rgba(0,0,0,0.3);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 32px;
  justify-content: center;
}

.brand h1 {
  font-size: 24px;
  font-weight: 700;
  margin: 0;
  background: linear-gradient(135deg, var(--text-primary), var(--pulsar));
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

h2 {
  text-align: center;
  font-size: 20px;
  font-weight: 600;
  margin: 0 0 24px;
  color: var(--text-primary);
}

:deep(.el-form-item__label) {
  font-weight: 500;
  color: var(--text-secondary);
}

.submit-btn {
  width: 100%;
  margin-top: 8px;
  height: 44px;
  font-size: 16px;
  background: var(--pulsar);
  color: #202020;
  border: none;
}

.submit-btn:hover {
  opacity: 0.9;
}

.login-link {
  text-align: center;
  margin-top: 16px;
  font-size: 14px;
  color: var(--text-secondary);
}

.login-link a {
  color: var(--pulsar);
  text-decoration: none;
  font-weight: 500;
}

.login-link a:hover {
  text-decoration: underline;
}
</style>
