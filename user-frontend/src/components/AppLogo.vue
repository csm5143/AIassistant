<template>
  <!-- Custom logo image -->
  <img v-if="branding.config.logoUrl" :src="branding.config.logoUrl" :width="size" :height="size" class="app-logo-img" alt="logo" />
  <!-- Default SVG -->
  <svg v-else viewBox="0 0 32 32" fill="none" :width="size" :height="size">
    <circle cx="16" cy="16" r="14" stroke="currentColor" stroke-width="1.2" opacity="0.35"/>
    <circle cx="16" cy="16" r="8" stroke="currentColor" stroke-width="1.2" opacity="0.65"/>
    <circle cx="16" cy="10" r="2.5" fill="currentColor"/>
    <line x1="16" y1="12.5" x2="16" y2="24" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
  </svg>
</template>

<script setup lang="ts">
import { useBrandingStore } from '@/stores/branding'
defineProps<{ size?: number }>()
const branding = useBrandingStore()
</script>

<style scoped>
.app-logo-img { border-radius: 6px; object-fit: contain; }
</style>
