<template>
  <div class="landing">
    <!-- ======== NAV ======== -->
    <nav :class="['landing-nav', { scrolled: isScrolled }]">
      <div class="nav-brand">
        <AppLogo :size="26" />
        <span class="nav-name">{{ branding.config.platformName }}</span>
      </div>
      <div class="nav-actions">
        <button class="nav-btn" @click="showAuth = true">登录</button>
        <button class="nav-btn primary" @click="$router.push('/chat')">开始使用</button>
      </div>
    </nav>

    <!-- ======== HERO: ORBITAL ======== -->
    <section class="hero-orbital" @click="resetOrbit">
      <!-- Subtle background grid -->
      <div class="orbital-bg"></div>

      <!-- Left: text -->
      <div class="hero-text-col">
        <div class="hero-badge">企业级 AI 平台</div>
        <h1 class="hero-title">
          <span class="title-line">智能对话，</span>
          <span class="title-line accent">不止于问答</span>
        </h1>
        <p class="hero-desc">
          从文档检索到代码生成，从数据分析到内容创作——<br/>
          {{ branding.config.platformName }} 是一个可扩展的 AI 能力平台，私有部署，安全可控。
        </p>
        <div class="hero-ctas">
          <button class="cta-primary" @click="$router.push('/chat')">
            开始使用
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/></svg>
          </button>
          <button class="cta-ghost" @click="scrollTo('skills')">
            探索能力
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="12" y1="5" x2="12" y2="19"/><polyline points="19 12 12 19 5 12"/></svg>
          </button>
        </div>
        <div class="hero-stats">
          <div class="stat-item"><span class="stat-num">4+</span><span class="stat-label">模型支持</span></div>
          <div class="stat-item"><span class="stat-num">7</span><span class="stat-label">内置技能</span></div>
          <div class="stat-item"><span class="stat-num">私有</span><span class="stat-label">部署方式</span></div>
        </div>
      </div>

      <!-- Right: orbital animation -->
      <div class="hero-orbital-col" ref="orbitalContainer">
        <div class="orbital-stage" :style="{ '--orbit-rotation': orbitAngle + 'deg' }">
          <!-- Outer ring decorations -->
          <div class="orbit-ring ring-1"></div>
          <div class="orbit-ring ring-2"></div>
          <div class="orbit-ring ring-3"></div>

          <!-- Central AI core -->
          <div class="orbit-core" @click="resetOrbit">
            <div class="core-inner">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <circle cx="12" cy="12" r="3"/><circle cx="12" cy="12" r="8" opacity="0.4"/>
                <path d="M12 2v3m0 14v3M2 12h3m14 0h3M4.93 4.93l2.12 2.12m9.9 9.9l2.12 2.12M4.93 19.07l2.12-2.12m9.9-9.9l2.12-2.12" opacity="0.5"/>
              </svg>
            </div>
            <div class="core-pulse"></div>
            <div class="core-pulse delay-1"></div>
            <div class="core-pulse delay-2"></div>
          </div>

          <!-- Orbital nodes -->
          <div
            v-for="(node, idx) in orbitalNodes"
            :key="node.id"
            class="orbit-node-wrapper"
            :style="nodeWrapperStyle(idx)"
            :class="{ active: activeNode === node.id, connected: isConnected(node.id), pulsing: pulseNodes.has(node.id) }"
            @click.stop="toggleNode(node.id)"
          >
            <!-- Energy aura ring (pulses on connected nodes) -->
            <div class="node-aura" :style="{
              '--aura-color': node.color,
              width: (node.energy * 0.45 + 38) + 'px',
              height: (node.energy * 0.45 + 38) + 'px',
            }"></div>

            <div class="orbit-node" :style="{ '--node-color': node.color }">
              <div class="node-dot" v-html="node.icon"></div>
              <span class="node-label">{{ node.title }}</span>
            </div>

            <!-- Expanded detail card (appears ABOVE node, outside orbit) -->
            <Transition name="card-pop">
              <div v-if="activeNode === node.id" class="orbit-card" @click.stop>
                <div class="orbit-card-body">
                  <div class="orbit-card-head">
                    <span class="card-badge" :style="{ background: node.color + '18', color: node.color }">{{ node.tag }}</span>
                    <span class="card-energy-label">{{ node.energy }}%</span>
                  </div>
                  <h3>{{ node.title }}</h3>
                  <p>{{ node.desc }}</p>
                  <!-- Energy bar -->
                  <div class="card-energy-bar">
                    <div class="energy-fill" :style="{ width: node.energy + '%', background: node.color }"></div>
                  </div>
                  <div v-if="node.points" class="card-points">
                    <span v-for="pt in node.points" :key="pt">{{ pt }}</span>
                  </div>
                  <!-- Connected nodes -->
                  <div v-if="node.connections.length" class="card-connections">
                    <span class="conn-label">关联能力</span>
                    <div class="conn-chips">
                      <button
                        v-for="cid in node.connections"
                        :key="cid"
                        class="conn-chip"
                        :style="{ '--chip-color': (orbitalNodes.find(n => n.id === cid) || node).color }"
                        @click.stop="toggleNode(cid)"
                      >
                        {{ orbitalNodes.find(n => n.id === cid)?.title || cid }}
                      </button>
                    </div>
                  </div>
                </div>
                <div class="orbit-card-connector"></div>
              </div>
            </Transition>
          </div>
        </div>
      </div>
    </section>

    <!-- ======== SKILLS ======== -->
    <section class="skills" id="skills">
      <div class="section-head">
        <div class="section-eyebrow">不只是对话</div>
        <h2 class="section-title">解锁更多 AI 能力</h2>
        <p class="section-sub">每个技能都是即插即用的智能工具，未来将不断扩展</p>
      </div>

      <div class="skills-grid">
        <div
          v-for="(s, i) in skills"
          :key="s.title"
          ref="skillRefs"
          class="skill-card"
          :style="{ '--accent': s.color, transitionDelay: i * 0.06 + 's' }"
        >
          <div class="skill-icon" v-html="s.icon"></div>
          <div class="skill-info">
            <h3>{{ s.title }}</h3>
            <p>{{ s.desc }}</p>
          </div>
          <div class="skill-tag">{{ s.tag }}</div>
        </div>
      </div>
    </section>

    <!-- ======== FEATURES ======== -->
    <section class="features" id="features">
      <div class="section-head">
        <div class="section-eyebrow">平台基石</div>
        <h2 class="section-title">企业级基础设施</h2>
        <p class="section-sub">安全、可审计、可扩展 — 为生产环境而生</p>
      </div>

      <div class="feature-grid">
        <div v-for="f in features" :key="f.title" class="feature-card">
          <div class="feature-icon" v-html="f.icon"></div>
          <h3>{{ f.title }}</h3>
          <p>{{ f.desc }}</p>
          <ul class="feature-points">
            <li v-for="pt in f.points" :key="pt">{{ pt }}</li>
          </ul>
        </div>
      </div>
    </section>

    <!-- ======== CTA ======== -->
    <section class="cta-section">
      <div class="cta-wrapper">
        <div class="cta-bg-glow"></div>
        <h2>把你的文档变成 AI 的智慧</h2>
        <p>上传文档、连接数据、启用技能。几分钟内拥有你的专属 AI 助手。</p>
        <button class="cta-primary large" @click="$router.push('/chat')">
          免费开始
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/></svg>
        </button>
      </div>
    </section>

    <!-- ======== FOOTER ======== -->
    <footer class="landing-footer">
      <div class="footer-grid">
        <div class="footer-brand">
          <span class="footer-logo">{{ branding.config.platformName }}</span>
          <p>{{ branding.config.platformSubtitle }}</p>
        </div>
        <div class="footer-links">
          <span class="footer-label">功能</span>
          <a>智能对话</a><a>知识库 RAG</a><a>技能市场</a><a>管理后台</a>
        </div>
        <div class="footer-links">
          <span class="footer-label">资源</span>
          <a>API 文档</a><a>部署指南</a><a>安全白皮书</a><a>更新日志</a>
        </div>
      </div>
      <div class="footer-bottom">
        <p>{{ branding.config.platformName }} &copy; 2026 · {{ branding.config.platformSubtitle }}</p>
      </div>
    </footer>

    <AuthModal v-model:visible="showAuth" @logged-in="$router.push('/chat')" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, type CSSProperties } from 'vue'
import AuthModal from '@/components/AuthModal.vue'
import AppLogo from '@/components/AppLogo.vue'
import { useBrandingStore } from '@/stores/branding'
const branding = useBrandingStore()

const showAuth = ref(false)
const isScrolled = ref(false)
const skillRefs = ref<HTMLElement[]>([])
const orbitalContainer = ref<HTMLElement | null>(null)

// ── Orbital state ──
const orbitAngle = ref(0)
const activeNode = ref<string | null>(null)
const autoRotate = ref(true)
const pulseNodes = ref(new Set<string>())

interface OrbitalNode {
  id: string
  title: string
  tag: string
  desc: string
  color: string
  icon: string
  energy: number
  points?: string[]
  connections: string[]
}

const orbitalNodes: OrbitalNode[] = [
  {
    id: 'chat', title: '智能对话', tag: '核心',
    desc: '多模型流式对话，Markdown + 代码高亮。支持 DeepSeek / OpenAI / Anthropic，会话记忆持久化。',
    color: '#e0e0e0', energy: 100,
    icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z"/></svg>',
    points: ['SSE 流式逐字输出', '多模型热切换', '上下文记忆窗口'],
    connections: ['rag', 'code'],
  },
  {
    id: 'rag', title: '知识库 RAG', tag: '检索',
    desc: '文档自动解析分块，向量语义检索。回答带原文引用溯源，支持 PDF/Word/Markdown。',
    color: '#a0a0a0', energy: 90,
    icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/></svg>',
    points: ['PDF / Word / Markdown', '混合搜索(向量+BM25)', '引用溯源可验证'],
    connections: ['chat', 'search'],
  },
  {
    id: 'code', title: '代码助手', tag: '开发',
    desc: '多语言代码生成、审查、重构。支持 Python / Java / TypeScript / Go 等 20+ 语言。',
    color: '#a0a0a0', energy: 85,
    icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>',
    points: ['20+ 编程语言', '代码审查 & 重构', '自然语言生成代码'],
    connections: ['chat'],
  },
  {
    id: 'search', title: '联网搜索', tag: '实时',
    desc: 'Agent 自动判断是否联网搜索，获取最新信息。支持新闻、天气、股价、赛事等实时数据。',
    color: '#cccccc', energy: 70,
    icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>',
    points: ['Agent 智能调度', '实时资讯获取', '网页内容抓取'],
    connections: ['rag', 'image'],
  },
  {
    id: 'image', title: '多模态识别', tag: '视觉',
    desc: '图片 OCR 文字提取 + Vision 视觉识别。截图转文字、图片描述、物体识别。',
    color: '#cccccc', energy: 65,
    icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>',
    points: ['OCR 文字提取', 'Vision 视觉识别', '图片内容描述'],
    connections: ['chat'],
  },
  {
    id: 'guard', title: '安全护栏', tag: '安全',
    desc: '关键词 + 正则双向过滤。输入拦截、输出脱敏，全链路审计日志。',
    color: '#cccccc', energy: 80,
    icon: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>',
    points: ['关键词 / 正则过滤', '输入拦截 + 输出脱敏', '审计日志全记录'],
    connections: ['chat', 'rag'],
  },
]

// ── Compute node positions around the orbit ──
const radius = 250
function nodeWrapperStyle(idx: number): CSSProperties {
  const total = orbitalNodes.length
  const baseAngle = (idx / total) * 360
  const angle = (baseAngle + orbitAngle.value) * (Math.PI / 180)
  const x = Math.cos(angle) * radius
  const y = Math.sin(angle) * radius
  const depth = (Math.sin(angle) + 1) / 2  // 0 (back) → 1 (front)
  const scale = 0.82 + depth * 0.18
  const opacity = 0.9 + depth * 0.1
  const zIndex = Math.round(50 + depth * 50)

  return {
    transform: `translate(${x}px, ${y}px) scale(${scale})`,
    opacity,
    zIndex,
  }
}

function isConnected(id: string): boolean {
  return pulseNodes.value.has(id)
}

// ── Orbit animation (unified rAF loop — smooth auto-rotate + cinematic snap) ──
let animFrameId: number | null = null
let snapAnimId: number | null = null
let lastFrameTime = 0

function orbitLoop(now: number) {
  const dt = lastFrameTime ? Math.min(now - lastFrameTime, 33) : 16 // cap at ~30fps min
  lastFrameTime = now

  if (!snapAnimId) {
    if (autoRotate.value) {
      orbitAngle.value = (orbitAngle.value + dt * 0.009) % 360 // ~0.15°/frame at 60fps
    }
  }

  animFrameId = requestAnimationFrame(orbitLoop)
}

function startOrbitAnimation() {
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  if (animFrameId) return
  lastFrameTime = 0
  animFrameId = requestAnimationFrame(orbitLoop)
}

function stopOrbitAnimation() {
  if (animFrameId) { cancelAnimationFrame(animFrameId); animFrameId = null }
  if (snapAnimId) { cancelAnimationFrame(snapAnimId); snapAnimId = null }
}

/** Cinematic snap: orbit glides to target with ease-out deceleration. */
function snapOrbitTo(target: number) {
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) { orbitAngle.value = target; return }
  if (snapAnimId) cancelAnimationFrame(snapAnimId)
  const start = orbitAngle.value
  let diff = target - start
  while (diff > 180) diff -= 360
  while (diff < -180) diff += 360
  if (Math.abs(diff) < 0.2) { orbitAngle.value = target; return }

  const startTime = performance.now()
  const duration = 800

  function step(now: number) {
    const progress = Math.min((now - startTime) / duration, 1)
    // Ease-out cubic: swift start → gentle deceleration → precise stop
    const eased = 1 - Math.pow(1 - progress, 3)
    orbitAngle.value = (start + diff * eased + 360) % 360
    if (progress < 1) {
      snapAnimId = requestAnimationFrame(step)
    } else {
      snapAnimId = null
    }
  }
  snapAnimId = requestAnimationFrame(step)
}

function toggleNode(id: string) {
  if (activeNode.value === id) {
    activeNode.value = null
    autoRotate.value = true
    pulseNodes.value = new Set()
    if (snapAnimId) { cancelAnimationFrame(snapAnimId); snapAnimId = null }
  } else {
    activeNode.value = id
    autoRotate.value = false
    const node = orbitalNodes.find(n => n.id === id)
    pulseNodes.value = new Set(node?.connections || [])
    // Snap to TOP (270°) with smooth animation
    const idx = orbitalNodes.findIndex(n => n.id === id)
    const nodeBaseAngle = (idx / orbitalNodes.length) * 360
    let target = 270 - nodeBaseAngle
    while (target < 0) target += 360
    snapOrbitTo(target % 360)
  }
}

function resetOrbit() {
  activeNode.value = null
  autoRotate.value = true
  pulseNodes.value = new Set()
  if (snapAnimId) { cancelAnimationFrame(snapAnimId); snapAnimId = null }
}

function onMotionPreferenceChange(event: MediaQueryListEvent) {
  if (event.matches) stopOrbitAnimation()
  else startOrbitAnimation()
}

// ── Scroll ──
function onScroll() { isScrolled.value = window.scrollY > 40 }
function scrollTo(id: string) { document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' }) }

// ── Intersection observer for cards ──
let skillObserver: IntersectionObserver | null = null

onMounted(() => {
  window.addEventListener('scroll', onScroll, { passive: true })
  window.matchMedia('(prefers-reduced-motion: reduce)').addEventListener('change', onMotionPreferenceChange)
  startOrbitAnimation()

  skillObserver = new IntersectionObserver(
    (entries) => { entries.forEach(e => { if (e.isIntersecting) (e.target as HTMLElement).classList.add('visible') }) },
    { threshold: 0.12, rootMargin: '0px 0px -30px 0px' }
  )
  setTimeout(() => document.querySelectorAll('.skill-card,.feature-card').forEach(el => skillObserver?.observe(el)), 200)
})

onUnmounted(() => {
  window.removeEventListener('scroll', onScroll)
  window.matchMedia('(prefers-reduced-motion: reduce)').removeEventListener('change', onMotionPreferenceChange)
  stopOrbitAnimation()
  skillObserver?.disconnect()
})

// ── Page content data ──
const skills = [
  { icon: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>', title: '代码助手', desc: '多语言代码生成、审查、重构、解释。支持 Python / Java / TS / Go 等 20+ 语言。', tag: '开发', color: '#e0e0e0' },
  { icon: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>', title: '文档摘要', desc: '长篇 PDF、合同、论文一键摘要。提取关键信息，生成结构化笔记。', tag: '效率', color: '#b8b8b8' },
  { icon: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>', title: '数据提取', desc: '从 PDF、Excel、扫描件中提取结构化数据，导出为 JSON / CSV / SQL。', tag: '数据', color: '#a0a0a0' },
  { icon: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><circle cx="12" cy="12" r="10"/><path d="M2 12h20"/><path d="M12 2a15.3 15.3 0 014 10 15.3 15.3 0 01-4 10 15.3 15.3 0 01-4-10 15.3 15.3 0 014-10z"/></svg>', title: '多语言翻译', desc: '实时翻译对话和文档，支持中/英/日/韩/法/德 等 30+ 语言对。', tag: '语言', color: '#cccccc' },
  { icon: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><rect x="3" y="3" width="18" height="18" rx="2"/><line x1="3" y1="9" x2="21" y2="9"/><line x1="9" y1="21" x2="9" y2="9"/></svg>', title: '数据分析', desc: '自然语言查询数据库，自动生成图表和洞察报告。支持 MySQL / PG。', tag: '分析', color: '#808080' },
  { icon: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 013 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>', title: '内容创作', desc: '撰写文章、邮件、报告、社交媒体内容。支持风格定制和品牌语调。', tag: '创作', color: '#cccccc' },
]

const features = [
  { icon: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z"/></svg>', title: '流式对话引擎', desc: 'SSE 实时输出，Markdown + 代码高亮。多模型无缝切换，对话历史持久化。', points: ['DeepSeek / OpenAI / Anthropic', '流式 SSE 逐字输出', '会话记忆窗口可配置'] },
  { icon: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/></svg>', title: '知识库 RAG', desc: '文档自动解析、智能分块、向量检索。回答带原文引用溯源。', points: ['PDF / Word / Markdown', 'pgvector 高性能检索', '引用溯源可验证'] },
  { icon: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>', title: '多层安全护栏', desc: '关键词 + 正则双向过滤。输入拦截、输出脱敏，全链路审计。', points: ['关键词 / 正则双层过滤', '输入拦截 + 输出脱敏', '审计日志全记录'] },
  { icon: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>', title: '管理后台', desc: '仪表盘统计、对话追溯、安全审计、模型配置、用户管理。', points: ['实时统计仪表盘', '模型热配置切换', '用户 + 配额管理'] },
]
</script>

<style scoped>
.landing { background: var(--bg-main); color: var(--void); font-family: var(--font-body); }

/* ===== NAV ===== */
.landing-nav {
  position: fixed; top: 0; left: 0; right: 0; z-index: 100;
  display: flex; align-items: center; justify-content: space-between;
  padding: 13px 36px; transition: all 0.4s var(--ease-out);
  background: transparent; border-bottom: 1px solid transparent;
}
.landing-nav.scrolled {
  background: rgba(245,245,245, 0.84);
  backdrop-filter: blur(18px);
  border-bottom-color: var(--horizon-soft);
}
.nav-brand { display: flex; align-items: center; gap: 10px; color: var(--void); }
.nav-name { font-family: var(--font-display); font-size: 16px; }
.nav-actions { display: flex; gap: 10px; }
.nav-btn {
  padding: 7px 18px; border-radius: var(--radius-sm);
  background: transparent; border: 1px solid var(--horizon-soft);
  color: var(--void); font-family: var(--font-body); font-size: 13px; font-weight: 500;
  cursor: pointer; transition: all var(--duration-fast) var(--ease-out);
}
.nav-btn:hover { border-color: var(--void); }
.nav-btn.primary { background: var(--void); border-color: var(--void); color: #fff; }
.nav-btn.primary:hover { background: var(--abyss); border-color: var(--abyss); }

/* ===== HERO ORBITAL ===== */
.hero-orbital {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 100px 36px 60px;
  overflow: hidden;
  background: radial-gradient(ellipse 70% 70% at 70% 50%, rgba(224,224,224, 0.06) 0%, transparent 60%),
              radial-gradient(ellipse 60% 80% at 30% 60%, rgba(160,160,160, 0.04) 0%, transparent 50%),
              var(--bg-main);
}

/* Subtle grid background */
.orbital-bg {
  position: absolute; inset: 0;
  background-image:
    linear-gradient(rgba(224,224,224, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(224,224,224, 0.04) 1px, transparent 1px);
  background-size: 60px 60px;
  mask-image: radial-gradient(ellipse 70% 70% at 65% 50%, black 30%, transparent 70%);
  pointer-events: none;
}

/* ── Left: text column ── */
.hero-text-col {
  flex: 0 0 420px;
  z-index: 10;
  animation: fadeSlideIn 0.8s var(--ease-out);
}
.hero-badge {
  display: inline-block; padding: 4px 12px; border-radius: 20px; margin-bottom: 20px;
  background: var(--pulsar-glow); color: var(--pulsar);
  font-size: 11px; font-weight: 600; letter-spacing: 0.08em; text-transform: uppercase;
}
.hero-title {
  font-family: var(--font-display);
  font-size: 3rem;
  font-weight: 400;
  line-height: 1.15;
  margin-bottom: 20px;
  letter-spacing: -0.02em;
  color: var(--void);
}
.title-line { display: block; }
.title-line.accent {
  background: linear-gradient(135deg, var(--pulsar) 0%, #cccccc 60%, var(--aurora) 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}
.hero-desc {
  font-size: 15px; color: var(--twilight); line-height: 1.7; margin-bottom: 28px; max-width: 440px;
}
.hero-ctas { display: flex; gap: 12px; align-items: center; margin-bottom: 32px; }
.cta-primary {
  display: inline-flex; align-items: center; gap: 8px;
  padding: 12px 28px; border-radius: var(--radius-sm);
  background: var(--pulsar); border: none;
  color: #fff; font-family: var(--font-body); font-size: 15px; font-weight: 500;
  cursor: pointer; transition: all var(--duration-fast) var(--ease-out);
}
.cta-primary:hover {
  background: var(--pulsar-deep);
  box-shadow: 0 0 24px rgba(224,224,224, 0.3);
  transform: translateY(-1px);
}
.cta-primary:active { transform: translateY(0) scale(0.98); }
.cta-primary.large { padding: 15px 40px; font-size: 16px; }
.cta-ghost {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 12px 20px; border-radius: var(--radius-sm);
  background: transparent; border: none; color: var(--twilight);
  font-family: var(--font-body); font-size: 14px; font-weight: 500;
  cursor: pointer; transition: all var(--duration-fast) var(--ease-out);
}
.cta-ghost:hover { color: var(--pulsar); }
.hero-stats { display: flex; gap: 32px; }
.stat-item { display: flex; flex-direction: column; }
.stat-num { font-family: var(--font-display); font-size: 1.5rem; color: var(--void); }
.stat-label { font-size: 11px; color: var(--twilight); margin-top: 2px; }

/* ── Right: orbital column ── */
.hero-orbital-col {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 520px;
  z-index: 5;
  animation: fadeSlideIn 1s var(--ease-out) 0.15s both;
}
.orbital-stage {
  position: relative;
  width: 600px;
  height: 600px;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* ── Orbit rings ── */
.orbit-ring {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  border: 1px solid transparent;
  pointer-events: none;
}
.orbit-ring.ring-1 {
  border-color: rgba(224,224,224, 0.08);
  animation: ringExpand 5s ease-in-out infinite;
}
.orbit-ring.ring-2 {
  border-color: rgba(224,224,224, 0.05);
  animation: ringExpand 5s ease-in-out 1s infinite;
}
.orbit-ring.ring-3 {
  inset: -20px;
  border-color: rgba(160,160,160, 0.04);
  border-style: dashed;
  animation: ringExpand 8s ease-in-out 2s infinite;
}
@keyframes ringExpand {
  0%, 100% { transform: scale(0.92); opacity: 0.6; }
  50%      { transform: scale(1); opacity: 1; }
}

/* ── Central core ── */
.orbit-core {
  position: absolute;
  width: 80px; height: 80px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--pulsar), #cccccc, var(--aurora));
  display: flex; align-items: center; justify-content: center;
  z-index: 30;
  cursor: pointer;
  transition: transform 0.3s var(--ease-out);
  box-shadow: 0 0 48px rgba(224,224,224, 0.3);
}
.orbit-core:hover { transform: scale(1.08); }
.core-inner {
  width: 38px; height: 38px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.9);
  display: flex; align-items: center; justify-content: center;
  color: var(--on-accent);
}
.core-inner svg { width: 28px; height: 28px; }
.core-pulse {
  position: absolute;
  inset: -10px;
  border-radius: 50%;
  border: 2px solid rgba(224,224,224, 0.3);
  animation: corePulse 2.5s ease-out infinite;
}
.core-pulse.delay-1 { animation-delay: 0.8s; }
.core-pulse.delay-2 { animation-delay: 1.6s; }
@keyframes corePulse {
  0%   { transform: scale(0.8); opacity: 0.8; }
  100% { transform: scale(1.6); opacity: 0; }
}

/* ── Orbital node ── */
.orbit-node-wrapper {
  position: absolute;
  left: 50%; top: 50%;
  margin-left: -26px; margin-top: -26px;
  width: 52px; height: 52px;
  transition: opacity 0.5s ease;
  cursor: pointer;
}

/* Energy aura ring — pulses on connected nodes */
.node-aura {
  position: absolute;
  border-radius: 50%;
  left: 50%; top: 50%;
  transform: translate(-50%, -50%);
  background: radial-gradient(circle, color-mix(in srgb, var(--aura-color) 25%, transparent) 0%, transparent 70%);
  opacity: 0;
  transition: opacity 0.5s ease;
  pointer-events: none;
}
.orbit-node-wrapper.pulsing .node-aura {
  opacity: 1;
  animation: auraPulse 0.8s ease-in-out infinite;
}
@keyframes auraPulse {
  0%, 100% { transform: translate(-50%, -50%) scale(0.9); opacity: 0.6; }
  50%      { transform: translate(-50%, -50%) scale(1.15); opacity: 1; }
}
.orbit-node-wrapper.connected .orbit-node {
  box-shadow: 0 0 18px var(--node-color), 0 0 36px color-mix(in srgb, var(--node-color) 40%, transparent);
}
.orbit-node-wrapper.active .node-aura {
  opacity: 1;
  animation: auraPulse 0.8s ease-in-out infinite;
}

.orbit-node {
  width: 52px; height: 52px;
  border-radius: 50%;
  background: #ffffff;
  border: 2px solid var(--node-color);
  display: flex; align-items: center; justify-content: center;
  color: var(--node-color);
  transition: all 0.35s cubic-bezier(0.16, 1, 0.3, 1);
  position: relative;
  box-shadow: 0 2px 14px color-mix(in srgb, var(--node-color) 15%, transparent);
}
.orbit-node:hover {
  transform: scale(1.18);
  box-shadow: 0 0 20px color-mix(in srgb, var(--node-color) 30%, transparent);
}
.orbit-node-wrapper.active .orbit-node {
  background: var(--node-color);
  color: #fff;
  border-color: var(--node-color);
  box-shadow: 0 0 28px color-mix(in srgb, var(--node-color) 50%, transparent);
  transform: scale(1.3);
}
.node-dot {
  width: 24px; height: 24px;
  display: flex; align-items: center; justify-content: center;
}
.node-dot svg { width: 18px; height: 18px; }
.node-label {
  position: absolute;
  top: 58px;
  left: 50%;
  transform: translateX(-50%);
  white-space: nowrap;
  font-size: 12px;
  font-weight: 600;
  color: var(--void);
  opacity: 1;
  transition: all 0.35s;
  pointer-events: none;
}
.orbit-node-wrapper.active .node-label { opacity: 1; color: var(--node-color); font-size: 13px; }
.orbit-node-wrapper.connected .node-label { opacity: 1; }

/* ── Expanded card (BELOW node, inside orbit circle) ── */
.orbit-card {
  position: absolute;
  top: 66px;
  left: 50%;
  transform: translateX(-50%);
  width: 360px;
  z-index: 60;
}
/* Connector line above card */
.orbit-card-connector {
  position: absolute;
  bottom: 100%;
  left: 50%;
  transform: translateX(-50%);
  width: 2px; height: 20px;
  background: linear-gradient(to bottom, var(--pulsar), rgba(224,224,224, 0.15));
  border-radius: 1px;
}
.orbit-card-body {
  background: #ffffff;
  border: 2px solid rgba(224,224,224, 0.35);
  border-radius: 16px;
  padding: 26px;
  box-shadow:
    0 2px 4px rgba(0, 0, 0, 0.08),
    0 8px 18px rgba(0, 0, 0, 0.12),
    0 0 0 1px rgba(0, 0, 0, 0.06);
}
.orbit-card-head {
  display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px;
}
.card-energy-label {
  font-size: 12px; font-weight: 700; color: #b0b0b0;
  font-variant-numeric: tabular-nums;
}
.orbit-card-body h3 {
  font-family: var(--font-display);
  font-size: 20px;
  font-weight: 600;
  margin: 0 0 10px;
  color: #111111;
}
.orbit-card-body p {
  font-size: 14px;
  color: #202020;
  line-height: 1.7;
  margin: 0 0 16px;
}
.card-badge {
  display: inline-block;
  padding: 4px 12px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

/* Energy bar */
.card-energy-bar {
  width: 100%; height: 5px;
  background: #e0e0e0;
  border-radius: 5px;
  overflow: hidden;
  margin-bottom: 18px;
}
.energy-fill {
  height: 100%; border-radius: 5px;
  transition: width 0.6s cubic-bezier(0.16, 1, 0.3, 1);
}

/* Points */
.card-points {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.card-points span {
  font-size: 12px;
  padding: 5px 12px;
  border-radius: 6px;
  background: #f5f5f5;
  color: #303030;
  border: 1px solid #e0e0e0;
  font-weight: 500;
}

/* Connected nodes chips */
.card-connections {
  margin-top: 18px; padding-top: 16px;
  border-top: 1.5px solid #e0e0e0;
}
.conn-label {
  font-size: 11px; font-weight: 600; text-transform: uppercase;
  letter-spacing: 0.08em; color: #b0b0b0;
  display: block; margin-bottom: 10px;
}
.conn-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.conn-chip {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 5px 14px; border-radius: 6px;
  background: transparent;
  border: 1.5px solid var(--chip-color);
  color: var(--chip-color);
  font-size: 13px; font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  font-family: inherit;
}
.conn-chip:hover {
  background: color-mix(in srgb, var(--chip-color) 14%, transparent);
  border-color: var(--chip-color);
  transform: translateY(-1px);
}
.conn-chip::after { content: '→'; font-size: 10px; opacity: 0.6; }

/* Card pop transition — cinematic entrance */
.card-pop-enter-active {
  transition: all 0.45s cubic-bezier(0.16, 1, 0.3, 1);
}
.card-pop-leave-active {
  transition: all 0.18s ease-in;
}
.card-pop-enter-from {
  opacity: 0;
  transform: translateX(-50%) translateY(-12px) scale(0.75);
}
.card-pop-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(-6px) scale(0.9);
}

/* ===== SKILLS ===== */
.skills { padding: 80px 36px 90px; background: var(--bg-subtle); }
.section-head { text-align: center; max-width: 560px; margin: 0 auto 48px; }
.section-eyebrow {
  font-size: 10px; font-weight: 600; text-transform: uppercase;
  letter-spacing: 0.12em; color: var(--twilight); margin-bottom: 10px;
}
.section-title {
  font-family: var(--font-display); font-size: 2rem;
  font-weight: 400; margin-bottom: 10px; color: var(--void);
}
.section-sub { font-size: 14px; color: var(--twilight); margin: 0; }

.skills-grid {
  max-width: 1040px; margin: 0 auto;
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px;
}
.skill-card {
  background: var(--bg-card); border: 1px solid var(--horizon-soft);
  border-radius: var(--radius); padding: 24px; position: relative; overflow: hidden;
  opacity: 0; transform: translateY(20px);
  transition: all 0.5s var(--ease-out), border-color var(--duration-fast), box-shadow var(--duration-fast);
}
.skill-card.visible { opacity: 1; transform: translateY(0); }
.skill-card::before {
  content: ''; position: absolute; top: 0; left: 0; right: 0; height: 3px;
  background: var(--accent); transform: scaleX(0); transform-origin: left;
  transition: transform 0.3s var(--ease-out);
}
.skill-card:hover { border-color: var(--accent); box-shadow: var(--shadow-md); }
.skill-card:hover::before { transform: scaleX(1); }
.skill-icon {
  width: 40px; height: 40px; border-radius: 8px;
  background: color-mix(in srgb, var(--accent) 12%, transparent);
  color: var(--accent); display: flex; align-items: center; justify-content: center;
  margin-bottom: 14px; transition: all var(--duration-fast);
}
.skill-card:hover .skill-icon { background: var(--accent); color: #fff; }
.skill-info h3 { font-family: var(--font-display); font-size: 1rem; font-weight: 400; margin-bottom: 5px; }
.skill-info p { font-size: 12.5px; color: var(--twilight); line-height: 1.55; margin: 0; }
.skill-tag {
  position: absolute; top: 14px; right: 14px;
  font-size: 10px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.06em;
  color: var(--text-secondary);
}

/* ===== FEATURES ===== */
.features { padding: 90px 36px 90px; }
.feature-grid {
  max-width: 1040px; margin: 0 auto;
  display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px;
}
.feature-card {
  background: var(--bg-card); border: 1px solid var(--horizon-soft);
  border-radius: var(--radius); padding: 30px;
  opacity: 0; transform: translateY(16px);
  transition: all 0.5s var(--ease-out), border-color var(--duration-fast), box-shadow var(--duration-fast);
}
.feature-card.visible { opacity: 1; transform: translateY(0); }
.feature-card:hover { border-color: var(--pulsar); box-shadow: var(--shadow-md); }
.feature-icon {
  width: 40px; height: 40px; border-radius: 8px;
  background: var(--pulsar-glow); color: var(--pulsar);
  display: flex; align-items: center; justify-content: center; margin-bottom: 14px;
  transition: all var(--duration-fast);
}
.feature-card:hover .feature-icon { background: var(--pulsar); color: #fff; }
.feature-card h3 { font-family: var(--font-display); font-size: 1.05rem; font-weight: 400; margin-bottom: 6px; }
.feature-card > p { font-size: 13px; color: var(--twilight); line-height: 1.6; margin: 0 0 12px; }
.feature-points { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 4px; }
.feature-points li { font-size: 12px; color: var(--twilight); padding-left: 16px; position: relative; }
.feature-points li::before { content: '—'; position: absolute; left: 0; color: var(--pulsar); font-weight: 600; }

/* ===== CTA ===== */
.cta-section { padding: 80px 36px 100px; }
.cta-wrapper {
  position: relative; max-width: 640px; margin: 0 auto; text-align: center;
  padding: 64px 48px; border-radius: var(--radius-xl);
  background: linear-gradient(135deg, #f5f5f5 0%, #f5f5f5 40%, #f5f5f5 100%);
  border: 1px solid var(--horizon-soft); overflow: hidden;
}
.cta-bg-glow {
  position: absolute; top: -60px; right: -60px; width: 260px; height: 260px;
  border-radius: 50%;
  background: radial-gradient(circle, var(--pulsar-glow) 0%, transparent 70%);
  pointer-events: none;
}
.cta-wrapper h2 {
  font-family: var(--font-display); font-size: 1.7rem; font-weight: 400;
  margin-bottom: 10px; position: relative; z-index: 1;
}
.cta-wrapper p { font-size: 14px; color: var(--twilight); margin-bottom: 24px; position: relative; z-index: 1; }

/* ===== FOOTER ===== */
.landing-footer { border-top: 1px solid var(--horizon-soft); padding: 48px 36px 28px; }
.footer-grid { max-width: 1040px; margin: 0 auto 32px; display: grid; grid-template-columns: 2fr 1fr 1fr; gap: 40px; }
.footer-logo { font-family: var(--font-display); font-size: 17px; color: var(--void); }
.footer-brand p { font-size: 12px; color: var(--twilight); margin-top: 4px; }
.footer-label {
  font-size: 11px; font-weight: 600; text-transform: uppercase;
  letter-spacing: 0.08em; color: var(--twilight); display: block; margin-bottom: 12px;
}
.footer-links a { display: block; font-size: 13px; color: var(--void); margin-bottom: 6px; cursor: pointer; transition: color var(--duration-fast); }
.footer-links a:hover { color: var(--pulsar); }
.footer-bottom { max-width: 1040px; margin: 0 auto; text-align: center; font-size: 11px; color: var(--twilight); }

/* ===== ANIMATIONS ===== */
@keyframes fadeSlideIn {
  from { opacity: 0; transform: translateY(16px); }
  to   { opacity: 1; transform: translateY(0); }
}

/* ===== RESPONSIVE ===== */
@media (max-width: 960px) {
  .hero-orbital {
    flex-direction: column;
    padding: 110px 24px 40px;
    gap: 32px;
  }
  .hero-text-col { flex: none; text-align: center; }
  .hero-desc { max-width: 100%; }
  .hero-ctas { justify-content: center; }
  .hero-stats { justify-content: center; }
  .hero-title { font-size: 2.2rem; }
  .hero-orbital-col { flex: none; min-height: 380px; }
  .orbital-stage { width: 440px; height: 440px; }
  .orbit-card { width: 280px; }
  .orbit-card-body { padding: 18px; }
  .skills-grid { grid-template-columns: repeat(2, 1fr); }
  .feature-grid { grid-template-columns: 1fr; }
  .footer-grid { grid-template-columns: 1fr; gap: 24px; }
}
@media (max-width: 480px) {
  .hero-title { font-size: 1.8rem; }
  .skills-grid { grid-template-columns: 1fr; }
  .hero-stats { gap: 16px; }
  .orbital-stage { width: 330px; height: 330px; }
  .orbit-card { width: 240px; }
  .orbit-card-body { padding: 14px; }
}

/* Bring the public entry page into the same charcoal palette. */
.landing, .nav-brand, .nav-btn, .hero-title, .stat-num, .node-label,
.section-title, .footer-logo, .footer-links a { color: var(--text-primary); }
.landing-nav.scrolled { background: rgba(0,0,0,.88); }
.nav-btn:hover { border-color: var(--pulsar); color: var(--pulsar); }
.nav-btn.primary, .cta-primary { background: var(--pulsar); border-color: var(--pulsar); color: #202020; }
.nav-btn.primary:hover, .cta-primary:hover { background: var(--pulsar-deep); border-color: var(--pulsar-deep); color: #202020; box-shadow: 0 8px 26px rgba(224,224,224,.16); }
.hero-orbital { background: radial-gradient(ellipse 62% 65% at 70% 45%, rgba(224,224,224,.075), transparent 70%), var(--bg-main); }
.orbital-bg { background-image: linear-gradient(rgba(224,224,224,.035) 1px, transparent 1px), linear-gradient(90deg, rgba(224,224,224,.035) 1px, transparent 1px); }
.title-line.accent { background: linear-gradient(110deg, var(--pulsar), #f5f5f5 56%, var(--aurora)); -webkit-background-clip: text; background-clip: text; }
.orbit-node, .orbit-card-body { background: #202020; }
.orbit-node-wrapper.active .orbit-node { color: var(--on-accent); background: #d9d9d9; border-color: #d9d9d9; }
.orbit-card-body { border-color: #606060; }
.card-energy-label, .orbit-card-body h3, .orbit-card-body p { color: var(--text-primary); }
.card-energy-bar { background: var(--bg-subtle); }
.card-points span { background: var(--bg-subtle); color: var(--text-secondary); border-color: var(--horizon-soft); }
.card-connections { border-color: var(--horizon-soft); }
.conn-label { color: var(--text-secondary); }
.cta-wrapper { background: linear-gradient(135deg, #282828, #202020 56%, #202020); }
.skill-card:hover .skill-icon, .feature-card:hover .feature-icon { color: var(--on-accent); background: #d9d9d9; }

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation: none !important; transition: none !important; }
  .orbital-stage { --orbit-rotation: 0deg !important; }
}
</style>
