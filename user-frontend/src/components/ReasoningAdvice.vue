<template>
  <section class="reasoning-advice" role="region" aria-label="思考模式建议">
    <div class="advice-content"><strong>这道问题建议用深度模式</strong><p>{{ explanation }}。</p><p class="advice-cost">预计比快速模式消耗更多 token，通常等待更久。额外用量取决于推理长度，发送前无法准确估算；按实际用量计入额度。深度模式也可能答错，关键结论仍需核对。</p></div>
    <div class="advice-actions"><button type="button" :disabled="disabled" @click="emit('apply')">{{ saving ? '正在切换…' : '切换为深度' }}</button><button type="button" :disabled="disabled" @click="emit('dismiss')">本题不提示</button><button type="button" :disabled="disabled" @click="emit('disable')">关闭建议</button></div>
    <small>由你选择后才切换，不会自动发送；后续消息沿用所选档位。</small>
  </section>
</template>
<script setup lang="ts">
defineProps<{ explanation: string; disabled: boolean; saving: boolean }>()
const emit = defineEmits<{ apply: []; dismiss: []; disable: [] }>()
</script>
<style scoped>
.reasoning-advice { margin: 0 12px 8px; padding: 10px 12px; border: 1px solid var(--control-border); border-radius: 10px; background: var(--bg-subtle); color: var(--text-primary); font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.advice-content strong { font-size: 13px; }.advice-content p { margin: 3px 0; }.advice-cost,small { color: var(--text-secondary); }small { display: block; margin-top: 5px; }
.advice-actions { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 7px; }.advice-actions button { border: 1px solid var(--control-border); border-radius: 7px; padding: 5px 9px; background: var(--bg-input); color: var(--text-primary); font: inherit; cursor: pointer; }.advice-actions button:first-child { font-weight: 600; }.advice-actions button:disabled { opacity: .55; cursor: default; }.advice-actions button:focus-visible { outline: 2px solid var(--citation); outline-offset: 2px; }
</style>
