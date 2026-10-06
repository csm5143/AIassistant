<template>
  <el-popover v-model:visible="open" placement="top-start" :width="292" trigger="click" popper-class="thinking-popover">
    <template #reference>
      <button class="thinking-trigger" :class="{ active: modelValue !== 'none' && supported }" :disabled="disabled || saving || !supported"
        :title="supported ? '选择思考强度' : '当前模型暂不支持调整思考强度'" :aria-label="`思考强度：${supported ? selected.label : '暂不支持'}`">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="m13 2-9 12h7l-1 8 10-12h-7l1-8Z"/></svg>
        <span>{{ supported ? selected.label : '思考' }}</span>
        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m6 9 6 6 6-6"/></svg>
      </button>
    </template>
    <div class="thinking-panel" :style="motionStyle">
      <div class="thinking-heading">
        <button class="thinking-shortcut" aria-label="切换为快速回答" :disabled="disabled || saving" @click="choose(0)"><svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="m13 2-9 12h7l-1 8 10-12h-7l1-8Z"/></svg></button>
        <div><strong>{{ levels[draft].label }}</strong><span class="thinking-model">{{ modelLabel }}</span></div>
        <button class="thinking-shortcut" aria-label="重置思考强度" :disabled="disabled || saving" @click="choose(0)"><svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><path d="M3 10a9 9 0 1 1 2 8M3 4v6h6"/></svg></button>
      </div>
      <div class="thinking-track" :class="{ 'is-boosting': boosting, 'is-disabled': disabled || saving }" :data-level="draft">
        <div class="thinking-ambient" aria-hidden="true"><i /></div>
        <div class="thinking-energy" aria-hidden="true">
          <div class="thinking-flow" />
          <div class="thinking-particles"><i v-for="particle in particles" :key="particle.id" :style="particle.style" /></div>
          <div class="thinking-orbits"><i /><i /></div>
          <div class="thinking-surge" />
        </div>
        <div class="thinking-ticks" aria-hidden="true"><i v-for="n in 4" :key="n" :class="{ reached: n - 1 <= draft }" /></div>
        <div class="thinking-thumb" aria-hidden="true" />
        <input v-model.number="draft" aria-label="思考强度" :aria-valuetext="levels[draft].label" type="range" min="0" max="3" step="1" :disabled="disabled || saving" @change="choose(draft)" />
      </div>
      <div class="thinking-labels"><button v-for="(level, index) in levels" :key="level.value" :class="{ selected: draft === index }" :disabled="disabled || saving" @click="choose(index)">{{ level.label }}</button></div>
      <p class="thinking-description" aria-live="polite">{{ saving ? '正在保存…' : levels[draft].description }}</p>
      <label class="thinking-advice-setting"><input type="checkbox" :checked="adviceEnabled" :disabled="disabled || saving" aria-label="复杂问题模式建议" @change="emit('adviceToggle', ($event.target as HTMLInputElement).checked)">复杂问题模式建议</label>
    </div>
  </el-popover>
</template>

<script setup lang="ts">
import { ref, watch, computed, onBeforeUnmount } from 'vue'
type Effort = 'none' | 'low' | 'high' | 'max'
const props = defineProps<{ modelValue: Effort; modelLabel: string; supported: boolean; disabled: boolean; saving: boolean; adviceEnabled?: boolean }>()
const emit = defineEmits<{ change: [effort: Effort]; adviceToggle: [enabled: boolean] }>()
const levels: { value: Effort; label: string; description: string }[] = [
  { value: 'none', label: '快速', description: '直接回答，适合日常问答和简单任务。' },
  { value: 'low', label: '轻度', description: '少量思考，兼顾速度与推理。' },
  { value: 'high', label: '深度', description: '更深入地推理，适合复杂分析。' },
  { value: 'max', label: '极高', description: '投入更多思考，通常需要更多时间与额度。' },
]
const open = ref(false), draft = ref(0)
const boosting = ref(false)
let boostTimer: ReturnType<typeof setTimeout> | undefined
const motionStyle = computed(() => ({
  '--level-fill': `calc(${draft.value / 3 * 100}% + ${15 - draft.value / 3 * 30}px)`,
  '--flow-duration': `${[0, 3.6, 2.1, 1.15][draft.value]}s`,
  '--particle-duration': `${[0, 3.8, 2.2, 1.25][draft.value]}s`,
  '--motion-state': open.value && !props.disabled && !props.saving ? 'running' : 'paused',
  '--effort-accent': ['#8bb9ff', '#a4baff', '#b5a1ff', '#c99aff'][draft.value],
}))
const particles = Array.from({ length: 14 }, (_, id) => ({
  id,
  style: { '--particle-x': `${6 + (id * 17 % 88)}%`, '--particle-y': `${18 + (id * 29 % 65)}%`, '--particle-delay': `${-(id * .47)}s`, '--particle-size': `${id % 4 === 0 ? 3 : 2}px` },
}))
watch(draft, (value, previous) => {
  if (!open.value || value <= previous) return
  clearTimeout(boostTimer)
  boosting.value = true
  boostTimer = setTimeout(() => { boosting.value = false }, 750)
})
onBeforeUnmount(() => { clearTimeout(boostTimer) })
const selected = computed(() => levels.find(l => l.value === props.modelValue) || levels[0])
watch(() => props.modelValue, value => { draft.value = Math.max(0, levels.findIndex(l => l.value === value)) }, { immediate: true })
watch(() => [props.disabled, props.supported], () => { if (props.disabled || !props.supported) open.value = false })
function choose(index: number) { if (props.disabled || props.saving || !props.supported) return; draft.value = index; emit('change', levels[index].value) }
</script>

<style>
.thinking-popover.el-popover { background: var(--bg-menu) !important; border: 1px solid var(--border) !important; border-radius: 20px !important; padding: 15px !important; color: var(--text-primary); box-shadow: var(--shadow-lg) !important; }
.thinking-popover .el-popper__arrow::before { background: var(--bg-menu) !important; border-color: var(--border) !important; }
.thinking-advice-setting { display: flex; align-items: center; gap: 7px; margin-top: 10px; padding-top: 9px; border-top: 1px solid var(--border); color: var(--text-secondary); font-size: 12px; cursor: pointer; }.thinking-advice-setting input { accent-color: var(--citation); }
.thinking-trigger { display: inline-flex; align-items: center; gap: 6px; flex-shrink: 0; height: 32px; padding: 0 9px; border: 0; border-radius: 9px; background: transparent; color: var(--text-secondary); font: inherit; font-size: 12px; white-space: nowrap; cursor: pointer; transition: background .18s ease, color .18s ease; }
.thinking-trigger:hover { background: var(--bg-subtle); color: var(--text-primary); }
.thinking-trigger.active { color: var(--citation); }
.thinking-trigger:disabled { opacity: .5; cursor: default; }
.thinking-trigger:focus-visible, .thinking-panel button:focus-visible { outline: 2px solid #8bb9ff; outline-offset: 3px; }
.thinking-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; text-align: center; }
.thinking-heading strong { display: block; color: var(--effort-accent); font-size: 15px; line-height: 22px; transition: color .3s ease; }
.thinking-model { display: block; color: var(--text-secondary); font-size: 11px; max-width: 175px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.thinking-shortcut { display: flex; padding: 5px; color: var(--text-secondary); background: transparent; border: 0; border-radius: 7px; cursor: pointer; }
.thinking-shortcut:hover { color: var(--text-primary); background: var(--bg-selected); }
.thinking-track { position: relative; height: 32px; border-radius: 20px; margin-top: 16px; background: #444; isolation: isolate; }
.thinking-ambient { display: none; position: absolute; inset: 0; border-radius: inherit; overflow: hidden; pointer-events: none; }
.thinking-track[data-level="0"] .thinking-ambient { display: block; }
.thinking-ambient i { position: absolute; left: -50px; top: 15px; width: 46px; height: 2px; background: linear-gradient(to right, transparent, #8bb9ff95, transparent); box-shadow: 0 0 8px #69a9ff55; animation: thinking-quick 2.5s cubic-bezier(.65, 0, .25, 1) infinite; animation-play-state: var(--motion-state); }
.thinking-energy { position: absolute; inset: 0 auto 0 0; width: var(--level-fill); border-radius: 20px; overflow: hidden; background: #347ff0; pointer-events: none; transition: width .42s cubic-bezier(.22, 1, .36, 1); }
.thinking-flow { position: absolute; inset: 0; background: linear-gradient(105deg, #306ce5 0%, #576cea 24%, #9170ec 51%, #bd80ed 72%, #6571ef 100%); background-size: 220% 100%; animation: thinking-flow var(--flow-duration) linear infinite; animation-play-state: var(--motion-state); opacity: .8; transition: opacity .3s ease; }
.thinking-track[data-level="0"] .thinking-flow, .thinking-track[data-level="0"] .thinking-particles { display: none; }
.thinking-track[data-level="1"] .thinking-flow { background: linear-gradient(110deg, #306ce5, #7469d8); animation-duration: 5s; }
.thinking-track[data-level="1"] .thinking-particles i { left: var(--particle-x); animation-name: thinking-twinkle; animation-duration: 2.6s; }
.thinking-track[data-level="1"] .thinking-particles i::before { display: none; }
.thinking-track[data-level="2"] .thinking-flow { background: linear-gradient(110deg, #436feb, #7561de, #976ee7, #576dea); opacity: .94; }
.thinking-track[data-level="2"] .thinking-particles { display: none; }
.thinking-track[data-level="3"] .thinking-flow { opacity: 1; }
.thinking-particles { position: absolute; inset: 0; pointer-events: none; }
.thinking-particles i { position: absolute; left: -16px; top: var(--particle-y); width: var(--particle-size); height: var(--particle-size); border-radius: 50%; background: #fff; box-shadow: 0 0 5px #ffffff80; animation: thinking-flight var(--particle-duration) linear infinite; animation-delay: var(--particle-delay); animation-play-state: var(--motion-state); }
.thinking-particles i::before { content: ''; position: absolute; right: 1px; top: 0; width: 4px; height: 100%; border-radius: inherit; background: linear-gradient(to right, transparent, #ffffff70); transition: width .3s ease; }
.thinking-track[data-level="2"] .thinking-particles i::before { width: 8px; }
.thinking-track[data-level="3"] .thinking-particles i::before { width: 14px; }
.thinking-orbits { display: none; position: absolute; inset: 0; pointer-events: none; }
.thinking-track[data-level="2"] .thinking-orbits { display: block; }
.thinking-orbits i { position: absolute; left: 8%; top: 7px; width: 66%; height: 18px; box-sizing: border-box; border: 1px solid #eadbffb0; border-radius: 50%; box-shadow: 0 0 7px #ceb2ff50; animation: thinking-orbit 4s ease-in-out infinite; animation-play-state: var(--motion-state); }
.thinking-orbits i + i { left: 26%; top: 7px; border-color: #b2d8ff95; animation-direction: reverse; animation-delay: -2s; }
.thinking-surge { position: absolute; inset: 0; background: linear-gradient(110deg, transparent 20%, #ffffff40 48%, transparent 72%); transform: translateX(-110%); }
.thinking-track.is-boosting .thinking-surge { animation: thinking-surge .7s cubic-bezier(.4, 0, .2, 1); }
.thinking-track[data-level="3"].is-boosting .thinking-particles i { animation-duration: .75s; }
.thinking-track.is-disabled .thinking-flow, .thinking-track.is-disabled .thinking-particles i { animation-play-state: paused; }
.thinking-thumb { position: absolute; z-index: 3; left: var(--level-fill); top: 1px; width: 30px; height: 30px; box-sizing: border-box; border: 1px solid #e7e7e7; border-radius: 50%; background: #fff; box-shadow: 0 1px 5px #0005; transform: translateX(-50%); pointer-events: none; transition: left .42s cubic-bezier(.22, 1, .36, 1), box-shadow .3s ease; }
.thinking-track[data-level="0"] .thinking-thumb::after { content: ''; position: absolute; inset: -1px; border: 1px solid #8bb9ff80; border-radius: inherit; animation: thinking-pulse 2.5s ease-out infinite; animation-play-state: var(--motion-state); }
.thinking-track.is-boosting .thinking-thumb { box-shadow: 0 1px 5px #0005, 0 0 14px #bf9bff80; }
.thinking-track input { position: relative; z-index: 4; display: block; appearance: none; -webkit-appearance: none; width: 100%; height: 32px; margin: 0; background: transparent; cursor: pointer; border-radius: 20px; }
.thinking-track input::-webkit-slider-thumb { appearance: none; width: 30px; height: 30px; border: 0; border-radius: 50%; background: transparent; }
.thinking-track input::-moz-range-thumb { width: 30px; height: 30px; border: 0; border-radius: 50%; background: transparent; }
.thinking-track input:focus-visible { outline: 2px solid #a5caff; outline-offset: 4px; }
.thinking-ticks { position: absolute; inset: 0 15px; display: flex; justify-content: space-between; align-items: center; pointer-events: none; }
.thinking-ticks i { width: 5px; height: 5px; border-radius: 50%; background: #aaa; }.thinking-ticks i.reached { background: #b3d2ff; }
.thinking-labels { display: flex; justify-content: space-between; margin-top: 8px; }
.thinking-labels button { padding: 4px 0; border: 0; color: var(--text-secondary); background: transparent; cursor: pointer; font: inherit; font-size: 11px; transition: color .3s ease; }.thinking-labels button.selected { color: var(--effort-accent); }
.thinking-description { min-height: 30px; margin: 10px 0 0; font-size: 11px; line-height: 17px; color: var(--text-secondary); }
@keyframes thinking-flow { from { background-position: 100% 50%; } to { background-position: -100% 50%; } }
@keyframes thinking-quick { 0%, 10% { transform: translateX(0); opacity: 0; } 25% { opacity: 1; } 68%, 100% { transform: translateX(360px); opacity: 0; } }
@keyframes thinking-pulse { 0%, 15% { transform: scale(1); opacity: .65; } 65%, 100% { transform: scale(1.42); opacity: 0; } }
@keyframes thinking-twinkle { 0%, 100% { transform: scale(.65); opacity: .2; } 45% { transform: scale(1.15); opacity: .95; } 65% { transform: scale(.9); opacity: .5; } }
@keyframes thinking-orbit { 0%, 100% { transform: rotate(-12deg) translateY(-5px); } 50% { transform: rotate(12deg) translateY(5px); } }
@keyframes thinking-flight { 0% { transform: translate3d(0, 0, 0); opacity: 0; } 12% { opacity: .75; } 80% { opacity: .9; } 100% { transform: translate3d(310px, 0, 0); opacity: 0; } }
@keyframes thinking-surge { to { transform: translateX(110%); } }
@media (prefers-reduced-motion: reduce) {
  .thinking-trigger, .thinking-heading strong, .thinking-energy, .thinking-thumb, .thinking-flow, .thinking-labels button { transition: none; }
  .thinking-flow, .thinking-particles i, .thinking-orbits i, .thinking-ambient i, .thinking-track[data-level="0"] .thinking-thumb::after, .thinking-track.is-boosting .thinking-surge { animation: none; }
  .thinking-particles, .thinking-surge, .thinking-ambient { display: none !important; }
}
</style>
