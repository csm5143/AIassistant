import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import { useBrandingStore } from './stores/branding'
import './style.css'
import { initializeTheme } from './composables/useTheme'

initializeTheme()

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// Load branding before mount
const branding = useBrandingStore()
branding.fetchBranding().finally(() => {
  document.title = branding.config.platformName || 'AI 助手平台'
  app.mount('#app')
})
