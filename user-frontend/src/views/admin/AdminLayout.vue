<template>
  <div class="admin-layout">
    <aside class="admin-sidebar">
      <div class="admin-logo">
        <span class="admin-brand">管理后台</span>
        <span class="admin-sub">{{ branding.config.platformName }}</span>
      </div>
      <el-menu
        :default-active="route.path"
        router
        background-color="transparent"
        text-color="var(--text-secondary)"
        active-text-color="var(--text-primary)"
      >
        <el-menu-item index="/admin/dashboard">
          <el-icon><DataAnalysis /></el-icon> 仪表盘
        </el-menu-item>
        <el-menu-item index="/admin/chat-logs">
          <el-icon><ChatLineSquare /></el-icon> 对话日志
        </el-menu-item>
        <el-menu-item index="/admin/guard-logs">
          <el-icon><Warning /></el-icon> 安全日志
        </el-menu-item>
        <el-menu-item index="/admin/tool-logs">
          <el-icon><Tools /></el-icon> 工具调用日志
        </el-menu-item>
        <el-menu-item index="/admin/users">
          <el-icon><User /></el-icon> 用户管理
        </el-menu-item>
        <el-menu-item index="/admin/configs">
          <el-icon><Setting /></el-icon> API配置
        </el-menu-item>
        <el-menu-item index="/admin/settings">
          <el-icon><Brush /></el-icon> 品牌设置
        </el-menu-item>
      </el-menu>
      <div class="admin-sidebar-footer">
        <el-button text @click="$router.push('/chat')" style="color:var(--text-secondary)">
          <el-icon><ArrowLeft /></el-icon> 返回前台
        </el-button>
      </div>
    </aside>
    <main class="admin-main">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'
import { ArrowLeft, DataAnalysis, ChatLineSquare, Warning, User, Setting, Brush, Tools } from '@element-plus/icons-vue'
import { useBrandingStore } from '@/stores/branding'
const branding = useBrandingStore()
const route = useRoute()
</script>

<style scoped>
.admin-layout { display: flex; height: 100vh; }
.admin-sidebar {
  width: 240px; flex-shrink: 0; background: var(--bg-sidebar); display: flex; flex-direction: column;
  border-right: 1px solid var(--border-light);
}
.admin-logo {
  padding: 20px 16px 14px; border-bottom: 1px solid var(--horizon);
}
.admin-brand {
  display: block;
  font-family: var(--font-display);
  font-size: 19px;
  font-weight: 400;
  color: var(--starlight);
  line-height: 1.2;
}
.admin-sub {
  display: block;
  font-family: var(--font-body);
  font-size: 10px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.12em;
  color: var(--twilight);
  margin-top: 2px;
}
.admin-sidebar :deep(.el-menu) { border-right: none; flex: 1; padding-top: 6px; }
.admin-sidebar :deep(.el-menu-item) {
  margin: 2px 8px; border-radius: 7px; font-size: 13px;
  transition: all var(--duration-fast) var(--ease-out);
}
.admin-sidebar :deep(.el-menu-item:hover) { background: var(--bg-subtle); }
.admin-sidebar :deep(.el-menu-item.is-active) { background: var(--pulsar-glow); }
.admin-sidebar-footer { padding: 10px; border-top: 1px solid var(--horizon); }
.admin-sidebar-footer :deep(.el-button) { font-size: 13px; }

.admin-main { flex: 1; min-width: 0; min-height: 0; overflow-y: auto; padding: 28px 32px; background: var(--bg-main); }
@media (max-width: 700px) {
  .admin-layout { height: 100dvh; flex-direction: column; }
  .admin-sidebar { width: 100%; border-right: 0; border-bottom: 1px solid var(--border-light); }
  .admin-logo { padding: 12px 16px; }
  .admin-sub { display: none; }
  .admin-sidebar :deep(.el-menu) { display: flex; flex: none; overflow-x: auto; padding: 4px; }
  .admin-sidebar :deep(.el-menu-item) { flex-shrink: 0; margin: 0 3px; height: 42px; line-height: 42px; padding: 0 12px; }
  .admin-sidebar-footer { padding: 4px 10px; }
  .admin-main { padding: 20px 16px; }
}
</style>
