import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { UserProfile, LoginResponse } from '@/api'
import { login as apiLogin, getMe } from '@/api'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('token') || '')
  const refreshToken = ref(localStorage.getItem('refreshToken') || '')
  const profile = ref<UserProfile | null>(null)

  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => profile.value?.scope === 'admin' || profile.value?.roles?.includes('ADMIN'))

  async function login(username: string, password: string, scope?: string) {
    const { data } = await apiLogin({ username, password, scope })
    const res = data.data
    token.value = res.accessToken
    refreshToken.value = res.refreshToken
    profile.value = res.profile
    localStorage.setItem('token', res.accessToken)
    localStorage.setItem('refreshToken', res.refreshToken)
    return res
  }

  async function fetchProfile() {
    try {
      const { data } = await getMe()
      profile.value = data.data
    } catch {
      logout()
    }
  }

  function logout() {
    token.value = ''
    refreshToken.value = ''
    profile.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('refreshToken')
  }

  return { token, refreshToken, profile, isLoggedIn, isAdmin, login, fetchProfile, logout }
})
