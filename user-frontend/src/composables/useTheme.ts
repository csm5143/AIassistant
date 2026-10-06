import { readonly, ref } from 'vue'

export type Theme = 'dark' | 'light'
const storageKey = 'aiassistant-theme'
const current = ref<Theme>('dark')

function apply(value: Theme) {
  current.value = value
  document.documentElement.style.removeProperty('--pulsar')
  document.documentElement.dataset.theme = value
  document.documentElement.classList.toggle('dark', value === 'dark')
}

export function initializeTheme() {
  let saved: string | null = null
  try { saved = localStorage.getItem(storageKey) } catch { /* Storage can be unavailable. */ }
  apply(saved === 'light' ? 'light' : 'dark')
  window.addEventListener('storage', event => {
    if (event.key === storageKey) apply(event.newValue === 'light' ? 'light' : 'dark')
  })
}

export function useTheme() {
  return {
    theme: readonly(current),
    setTheme(value: Theme) {
      apply(value)
      try { localStorage.setItem(storageKey, value) } catch { /* Keep the in-memory choice. */ }
    },
  }
}
