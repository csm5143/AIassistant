<template>
  <div class="users-page">
    <div class="page-header">
      <h2>用户管理</h2>
      <div class="header-actions">
        <el-input v-model="keyword" placeholder="搜索用户名/邮箱" clearable @change="loadUsers" style="width: 240px">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button :icon="Refresh" @click="loadUsers">刷新</el-button>
      </div>
    </div>

    <el-table :data="users" v-loading="loading" stripe>
      <el-table-column prop="username" label="用户名" width="140" />
      <el-table-column prop="displayName" label="显示名" width="140">
        <template #default="{ row }">{{ row.displayName || '-' }}</template>
      </el-table-column>
      <el-table-column prop="email" label="邮箱" width="180">
        <template #default="{ row }">{{ row.email || '-' }}</template>
      </el-table-column>
      <el-table-column prop="phone" label="手机" width="140">
        <template #default="{ row }">{{ row.phone || '-' }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
            {{ row.status === 1 ? '正常' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="注册时间" width="160">
        <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="260">
        <template #default="{ row }">
          <el-button size="small" @click="editQuota(row)">配额</el-button>
          <el-button size="small" @click="toggleStatus(row)">
            {{ row.status === 1 ? '禁用' : '启用' }}
          </el-button>
          <el-button size="small" type="danger" @click="deleteUser(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        @current-change="loadUsers"
        @size-change="loadUsers"
      />
    </div>

    <!-- Quota Dialog -->
    <el-dialog v-model="quotaDialogVisible" :title="`用户配额 - ${selectedUser?.username}`" width="600px">
      <el-form :model="quotaForm" label-width="120px" v-if="quota">
        <el-divider content-position="left">Token 配额</el-divider>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="日 Token 限额">
              <el-input-number v-model="quotaForm.dailyTokenLimit" :min="-1" :max="100000000" controls-position="right" style="width: 100%"/>
              <span class="form-hint">-1 表示无限</span>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="月 Token 限额">
              <el-input-number v-model="quotaForm.monthlyTokenLimit" :min="-1" :max="1000000000" controls-position="right" style="width: 100%"/>
              <span class="form-hint">-1 表示无限</span>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="日 Token 已用">
              <el-input-number v-model="quotaForm.dailyTokenUsed" :min="0" controls-position="right" style="width: 100%"/>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="月 Token 已用">
              <el-input-number v-model="quotaForm.monthlyTokenUsed" :min="0" controls-position="right" style="width: 100%"/>
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">请求配额</el-divider>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="日请求限额">
              <el-input-number v-model="quotaForm.dailyRequestLimit" :min="-1" :max="100000" controls-position="right" style="width: 100%"/>
              <span class="form-hint">-1 表示无限</span>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="月请求限额">
              <el-input-number v-model="quotaForm.monthlyRequestLimit" :min="-1" :max="1000000" controls-position="right" style="width: 100%"/>
              <span class="form-hint">-1 表示无限</span>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="日请求已用">
              <el-input-number v-model="quotaForm.dailyRequestUsed" :min="0" controls-position="right" style="width: 100%"/>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="月请求已用">
              <el-input-number v-model="quotaForm.monthlyRequestUsed" :min="0" controls-position="right" style="width: 100%"/>
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">配额重置</el-divider>
        <el-form-item label="重置时间">
          <el-date-picker v-model="quotaForm.quotaResetAt" type="datetime" placeholder="选择重置时间" style="width: 100%"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="quotaDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveQuota" :loading="quotaSaving">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { getUsers, enableUser, disableUser, deleteUser as apiDeleteUser, getUserQuota, updateUserQuota } from '@/api'
import type { SysUser, UserQuota } from '@/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'

const users = ref<SysUser[]>([])
const loading = ref(false)
const keyword = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)

const quotaDialogVisible = ref(false)
const quotaSaving = ref(false)
const selectedUser = ref<SysUser | null>(null)
const quota = ref<UserQuota | null>(null)

const quotaForm = reactive({
  dailyTokenLimit: 0,
  monthlyTokenLimit: 0,
  dailyTokenUsed: 0,
  monthlyTokenUsed: 0,
  dailyRequestLimit: 0,
  monthlyRequestLimit: 0,
  dailyRequestUsed: 0,
  monthlyRequestUsed: 0,
  quotaResetAt: ''
})

async function loadUsers() {
  loading.value = true
  try {
    const { data } = await getUsers({ page: page.value, size: size.value, keyword: keyword.value || undefined })
    users.value = data.data.records || []
    total.value = data.data.total || 0
  } catch {
    ElMessage.error('加载用户失败')
  } finally {
    loading.value = false
  }
}

async function toggleStatus(user: SysUser) {
  try {
    if (user.status === 1) {
      await disableUser(user.id)
      ElMessage.success('已禁用')
    } else {
      await enableUser(user.id)
      ElMessage.success('已启用')
    }
    loadUsers()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '操作失败')
  }
}

async function deleteUser(user: SysUser) {
  try {
    await ElMessageBox.confirm(`确定删除用户 ${user.username}？`, '确认删除', { type: 'warning' })
    await apiDeleteUser(user.id)
    ElMessage.success('已删除')
    loadUsers()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e.response?.data?.message || '删除失败')
    }
  }
}

async function editQuota(user: SysUser) {
  selectedUser.value = user
  try {
    const { data } = await getUserQuota(user.id)
    quota.value = data.data
    Object.assign(quotaForm, {
      dailyTokenLimit: data.data.dailyTokenLimit,
      monthlyTokenLimit: data.data.monthlyTokenLimit,
      dailyTokenUsed: data.data.dailyTokenUsed,
      monthlyTokenUsed: data.data.monthlyTokenUsed,
      dailyRequestLimit: data.data.dailyRequestLimit,
      monthlyRequestLimit: data.data.monthlyRequestLimit,
      dailyRequestUsed: data.data.dailyRequestUsed,
      monthlyRequestUsed: data.data.monthlyRequestUsed,
      quotaResetAt: data.data.quotaResetAt
    })
    quotaDialogVisible.value = true
  } catch {
    ElMessage.error('加载配额失败')
  }
}

async function saveQuota() {
  if (!selectedUser.value) return
  quotaSaving.value = true
  try {
    await updateUserQuota(selectedUser.value.id, quotaForm)
    ElMessage.success('配额已保存')
    quotaDialogVisible.value = false
  } catch {
    ElMessage.error('保存失败')
  } finally {
    quotaSaving.value = false
  }
}

function formatDate(dateStr: string) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('zh-CN')
}

onMounted(loadUsers)
</script>

<style scoped>
.users-page { padding: 24px; }
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.page-header h2 { font-size: 20px; font-weight: 600; margin: 0; }
.header-actions { display: flex; gap: 10px; }
.pagination { margin-top: 20px; display: flex; justify-content: flex-end; }
.form-hint { font-size: 12px; color: var(--text-light); margin-left: 8px; }
</style>
