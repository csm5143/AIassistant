<template>
  <div ref="anchor" class="account-anchor" :class="{ compact }">
    <button ref="trigger" class="account-trigger" :aria-expanded="open" aria-haspopup="menu" aria-controls="account-menu" aria-label="打开账户菜单" @click="toggle" @keydown.down.prevent="show(true)" @keydown.up.prevent="show(true)">
      <span class="account-avatar">{{ initial }}</span>
      <span v-if="!compact" class="account-name">{{ name }}</span>
      <el-icon v-if="!compact" class="account-more"><MoreFilled /></el-icon>
    </button>
    <Teleport to="body">
      <Transition name="account-pop">
        <section v-if="open" id="account-menu" ref="menu" class="account-menu" :style="position" role="menu" aria-label="账户菜单" @keydown="onKeydown">
          <div class="account-heading"><span class="account-avatar">{{ initial }}</span><div><strong>{{ name }}</strong><small>{{ auth.isLoggedIn ? (auth.isAdmin ? '管理员' : '个人账户') : '尚未登录' }}</small></div></div>
          <div class="account-divider" />
          <button role="menuitem" @click="navigate('/knowledge')"><el-icon><Folder /></el-icon><span>知识库</span><el-icon class="account-chevron"><ArrowRight /></el-icon></button>
          <button role="menuitem" @click="navigate('/research')"><el-icon><Search /></el-icon><span>专题研究</span><el-icon class="account-chevron"><ArrowRight /></el-icon></button>
          <button role="menuitem" @click="navigate('/evaluations')"><el-icon><Monitor /></el-icon><span>评测看板</span><el-icon class="account-chevron"><ArrowRight /></el-icon></button>
          <button v-if="auth.isAdmin" role="menuitem" @click="navigate('/admin/dashboard')"><el-icon><Monitor /></el-icon><span>管理</span><el-icon class="account-chevron"><ArrowRight /></el-icon></button>
          <div class="account-divider" />
          <button role="menuitem" :aria-expanded="appearanceOpen" aria-controls="account-appearance" @click="appearanceOpen = !appearanceOpen">
            <el-icon><component :is="theme === 'dark' ? Moon : Sunny" /></el-icon><span>外观</span><small>{{ theme === 'dark' ? '深灰' : '浅色' }}</small><el-icon class="appearance-chevron" :class="{ expanded: appearanceOpen }"><ArrowRight /></el-icon>
          </button>
          <Transition name="appearance-slide">
            <div v-if="appearanceOpen" id="account-appearance" class="account-appearance">
              <button role="menuitemradio" :aria-checked="theme === 'dark'" :class="{ selected: theme === 'dark' }" @click="setTheme('dark')"><el-icon><Moon /></el-icon>深灰</button>
              <button role="menuitemradio" :aria-checked="theme === 'light'" :class="{ selected: theme === 'light' }" @click="setTheme('light')"><el-icon><Sunny /></el-icon>浅色</button>
            </div>
          </Transition>
          <div class="account-divider" />
          <button v-if="auth.isLoggedIn" role="menuitem" @click="logout"><el-icon><SwitchButton /></el-icon><span>退出登录</span></button>
          <button v-else role="menuitem" @click="login"><el-icon><User /></el-icon><span>登录</span></button>
        </section>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ArrowRight, Folder, Monitor, Moon, MoreFilled, Search, Sunny, SwitchButton, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useTheme } from '@/composables/useTheme'

const props = defineProps<{ compact?: boolean }>()
const emit = defineEmits<{ navigate: [path: string]; logout: []; login: [] }>()
const auth = useAuthStore()
const { theme, setTheme } = useTheme()
const name = computed(() => auth.profile?.displayName || auth.profile?.username || '访客')
const initial = computed(() => Array.from(name.value)[0]?.toUpperCase() || 'U')
const anchor = ref<HTMLElement>(), trigger = ref<HTMLButtonElement>(), menu = ref<HTMLElement>()
const open = ref(false), appearanceOpen = ref(false)
const position = ref<Record<string, string>>({})

function place() {
  const box = trigger.value?.getBoundingClientRect()
  if (!box) return
  const width = Math.min(292, window.innerWidth - 20)
  position.value = { width: `${width}px`, left: `${Math.max(10, Math.min(box.left, window.innerWidth - width - 10))}px`, bottom: `${window.innerHeight - box.top + 8}px`, maxHeight: `${Math.max(100, box.top - 18)}px` }
}
async function show(focus = false) {
  place(); open.value = true
  await nextTick()
  if (focus) menu.value?.querySelector<HTMLButtonElement>('button')?.focus()
}
function close(restore = false) { open.value = false; if (restore) trigger.value?.focus() }
function toggle() { if (open.value) close(); else void show() }
function navigate(path: string) { close(); emit('navigate', path) }
function logout() { close(); emit('logout') }
function login() { close(); emit('login') }
function outside(event: PointerEvent) {
  if (!(event.target instanceof Node) || anchor.value?.contains(event.target) || menu.value?.contains(event.target)) return
  close()
}
function escape(event: KeyboardEvent) { if (event.key === 'Escape' && open.value) { event.preventDefault(); close(true) } }
function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Tab') { close(); return }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const buttons = Array.from(menu.value?.querySelectorAll<HTMLButtonElement>('button') || [])
  const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length
  buttons[next]?.focus()
}
watch(() => props.compact, () => close())
onMounted(() => { document.addEventListener('pointerdown', outside); document.addEventListener('keydown', escape); window.addEventListener('resize', place) })
onUnmounted(() => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape); window.removeEventListener('resize', place) })
</script>

<style scoped>
.account-anchor { min-width: 0; width: 100%; }
.account-trigger { display: flex; align-items: center; gap: 10px; width: 100%; min-height: 48px; padding: 8px; border: 0; border-radius: 10px; background: transparent; color: var(--text-primary); cursor: pointer; font: inherit; text-align: left; }
.account-trigger:hover, .account-trigger[aria-expanded=true] { background: var(--bg-subtle); }
.account-avatar { display: grid; place-items: center; flex: 0 0 30px; height: 30px; border-radius: 50%; background: var(--bg-selected); color: var(--text-primary); border: 1px solid var(--border); font-size: 13px; font-weight: 600; }
.account-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.account-more { color: var(--text-secondary); font-size: 18px; }
.compact .account-trigger { padding: 6px 0; justify-content: center; }
.account-menu { z-index: 2100; position: fixed; padding: 8px; overflow-y: auto; border: 1px solid var(--border); border-radius: 15px; background: var(--bg-menu); color: var(--text-primary); box-shadow: var(--shadow-lg); transform-origin: bottom left; }
.account-heading { display: flex; gap: 10px; padding: 8px 9px 10px; align-items: center; }
.account-heading div { display: grid; gap: 2px; min-width: 0; }
.account-heading strong { font-size: 14px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.account-heading small, .account-menu button small { color: var(--text-secondary); font-size: 12px; }
.account-divider { height: 1px; background: var(--border-light); margin: 5px 7px; }
.account-menu button { display: flex; gap: 10px; align-items: center; width: 100%; min-height: 39px; padding: 8px 10px; border: 0; border-radius: 8px; background: transparent; color: var(--text-primary); font: inherit; font-size: 14px; cursor: pointer; text-align: left; }
.account-menu button:hover { background: var(--bg-selected); }
.account-menu button > span:not(.el-icon) { flex: 1; }
.account-menu .el-icon { font-size: 17px; color: var(--text-secondary); }
.account-chevron, .appearance-chevron { font-size: 13px !important; }
.appearance-chevron { transition: transform .22s ease; }
.appearance-chevron.expanded { transform: rotate(90deg); }
.account-appearance { display: flex; gap: 5px; margin: 3px 8px 6px; padding: 4px; border-radius: 10px; background: var(--bg-subtle); overflow: hidden; }
.account-appearance button { justify-content: center; font-size: 13px; min-height: 35px; }
.account-appearance button.selected { background: var(--bg-card); box-shadow: var(--shadow-xs); outline: 1px solid var(--border); }
.account-pop-enter-active, .account-pop-leave-active { transition: opacity .18s ease, transform .24s cubic-bezier(.2,.9,.2,1); }
.account-pop-enter-from, .account-pop-leave-to { opacity: 0; transform: translateY(9px) scale(.94); }
.appearance-slide-enter-active, .appearance-slide-leave-active { transition: opacity .2s ease, max-height .25s ease, margin .25s ease, padding .25s ease; max-height: 54px; }
.appearance-slide-enter-from, .appearance-slide-leave-to { opacity: 0; max-height: 0; margin-block: 0; padding-block: 0; }
@media(prefers-reduced-motion:reduce) { .account-pop-enter-active, .account-pop-leave-active, .appearance-slide-enter-active, .appearance-slide-leave-active, .appearance-chevron { transition: none; } }
</style>
