import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/evaluations', name: 'Evaluations', component: () => import('@/views/Evaluations.vue') },
    {
      path: '/',
      name: 'Landing',
      component: () => import('@/views/Landing.vue'),
    },
    {
      path: '/chat',
      redirect: '/chat/new',
    },
    {
      path: '/chat/:sessionId',
      name: 'Chat',
      component: () => import('@/views/Chat.vue'),
    },
    {
      path: '/knowledge',
      name: 'Knowledge',
      component: () => import('@/views/Knowledge.vue'),
      meta: { auth: true },
    },
    {
      path: '/research/:sessionId?',
      name: 'Research',
      component: () => import('@/views/Research.vue'),
      meta: { auth: true },
    },
    {
      path: '/admin',
      component: () => import('@/views/admin/AdminLayout.vue'),
      meta: { auth: true, admin: true },
      children: [
        { path: '', redirect: '/admin/dashboard' },
        { path: 'dashboard', name: 'AdminDashboard', component: () => import('@/views/admin/Dashboard.vue') },
        { path: 'chat-logs', name: 'AdminChatLogs', component: () => import('@/views/admin/ChatLogs.vue') },
        { path: 'guard-logs', name: 'AdminGuardLogs', component: () => import('@/views/admin/GuardLogs.vue') },
        { path: 'tool-logs', name: 'AdminToolLogs', component: () => import('@/views/admin/ToolCallLogs.vue') },
        { path: 'users', name: 'AdminUsers', component: () => import('@/views/admin/Users.vue') },
        { path: 'configs', name: 'AdminConfigs', component: () => import('@/views/admin/ConfigManagement.vue') },
        { path: 'settings', name: 'AdminSettings', component: () => import('@/views/admin/Settings.vue') },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
})

router.beforeEach(async (to, _from, next) => {
  const auth = useAuthStore()

  // Restore profile from token if needed
  if (auth.isLoggedIn && !auth.profile) {
    try {
      await auth.fetchProfile()
    } catch {
      auth.logout()
    }
  }

  // Auth-required pages
  if (to.meta.auth && !auth.isLoggedIn) {
    // Store intended path so chat can show auth modal and redirect
    sessionStorage.setItem('authRedirect', to.fullPath)
    return next('/chat')
  }

  // Admin-only pages
  if (to.meta.admin && !auth.isAdmin) {
    return next('/chat')
  }

  next()
})

export default router
