<template>
  <article class="api-card" :class="{ inactive: !model.enabled }">
    <header><div><span class="api-purpose">{{ label }}</span><h3 :title="model.name">{{ model.name }}</h3></div>
      <el-switch :model-value="model.enabled" :disabled="busy" :aria-label="`启用 ${model.name}`" @change="$emit('toggle', model)" /></header>
    <p class="api-model" :title="model.modelName">{{ model.modelName }}</p>
    <p class="api-address" :title="model.baseUrl">{{ model.baseUrl }}</p>
    <div class="api-status"><span :class="hasSavedKey(model) ? 'key-saved' : 'key-missing'">{{ hasSavedKey(model) ? '密钥已保存' : '待配置密钥' }}</span><span>{{ model.enabled ? '已启用' : '已停用' }}</span></div>
    <footer><el-button type="primary" plain @click="$emit('edit', model)">配置</el-button><el-button :loading="testing" @click="$emit('test', model)">测试连接</el-button>
      <el-popconfirm :title="`删除「${model.name}」配置？`" @confirm="$emit('delete', model)"><template #reference><el-button class="delete-button" text type="danger">删除</el-button></template></el-popconfirm></footer>
  </article>
</template>
<script setup lang="ts">
import { hasSavedKey, type ModelConfiguration } from '@/utils/modelConfiguration'
defineProps<{ model: ModelConfiguration; label: string; testing?: boolean; busy?: boolean }>()
defineEmits<{ edit: [model: ModelConfiguration]; test: [model: ModelConfiguration]; toggle: [model: ModelConfiguration]; delete: [model: ModelConfiguration] }>()
</script>
<style scoped>
.api-card { min-width: 0; padding: 20px; border: 1px solid var(--horizon-soft); border-radius: 14px; background: var(--bg-card); }
.api-card.inactive { background: var(--bg-subtle); }
header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
header > div { min-width: 0; }
.api-purpose { font-size: 12px; color: var(--twilight); }
h3 { margin: 6px 0 0; font-size: 16px; overflow-wrap: anywhere; }
.api-model { margin: 16px 0 6px; font-size: 13px; overflow-wrap: anywhere; }
.api-address { margin: 0; color: var(--twilight); font-size: 12px; overflow-wrap: anywhere; }
.api-status { display: flex; flex-wrap: wrap; gap: 14px; margin-top: 16px; color: var(--twilight); font-size: 12px; }
.key-saved { color: #b0b0b0; } .key-missing { color: var(--flare); }
footer { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; border-top: 1px solid var(--horizon-soft); padding-top: 14px; margin-top: 16px; }
footer :deep(.el-button + .el-button) { margin-left: 0; } .delete-button { margin-left: auto !important; }
</style>
