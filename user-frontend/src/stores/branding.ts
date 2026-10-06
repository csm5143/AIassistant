import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '@/api'

export interface BrandingConfig {
  platformName: string
  platformSubtitle: string
  sidebarTitle: string
  favicon: string
  primaryColor: string
  logoUrl: string
  logoType: string
}

export const monochromeAccents = [
  { label: '亮白', value: '#f5f5f5' },
  { label: '银灰', value: '#e0e0e0' },
  { label: '浅灰', value: '#cccccc' },
]

function normalizeAccent(value: unknown): string {
  const color = String(value || '').toLowerCase()
  // Migrate old brand colors and keep every supported accent readable on black.
  return monochromeAccents.some(option => option.value === color) ? color : '#f5f5f5'
}

export const useBrandingStore = defineStore('branding', () => {
  const config = ref<BrandingConfig>({
    platformName: 'Observatory',
    platformSubtitle: '企业级 AI 助手平台',
    sidebarTitle: 'AI 助手',
    favicon: '/vite.svg',
    primaryColor: '#f5f5f5',
    logoUrl: '',
    logoType: 'observatory',
  })

  function applyFavicon(url: string) {
    if (!url) return
    // Ensure absolute path
    if (!url.startsWith('http') && !url.startsWith('/')) url = '/' + url
    let link = document.querySelector<HTMLLinkElement>('link[rel="icon"]')
    if (!link) {
      link = document.createElement('link')
      link.rel = 'icon'
      document.head.appendChild(link)
    }
    link.href = url
  }

  async function fetchBranding() {
    try {
      const { data } = await api.get('/admin/settings/public')
      if (data.data) {
        const primaryColor = normalizeAccent(data.data.primaryColor)
        Object.assign(config.value, data.data, { primaryColor })
        applyFavicon(data.data.favicon)
        // Brand accents apply to the dark palette; the light palette supplies a readable dark accent.
        document.documentElement.style.removeProperty('--pulsar')
        document.documentElement.style.setProperty('--brand-accent', primaryColor)
      }
    } catch { /* use defaults */ }
  }

  const keyMap: Record<string, string> = {
    platformName: 'platform.name',
    platformSubtitle: 'platform.subtitle',
    sidebarTitle: 'sidebar.title',
    favicon: 'favicon',
    logoUrl: 'logo.url',
    primaryColor: 'color.primary',
  }

  async function saveBranding(updates: Partial<BrandingConfig>) {
    const body: Record<string, string> = {}
    const normalized = updates.primaryColor === undefined ? updates
      : { ...updates, primaryColor: normalizeAccent(updates.primaryColor) }
    for (const [k, v] of Object.entries(normalized)) {
      if (v !== undefined) body[keyMap[k] || k] = String(v)
    }
    await api.put('/admin/settings/branding', body)
    Object.assign(config.value, normalized)
    await fetchBranding()
  }

  return { config, fetchBranding, saveBranding }
})
