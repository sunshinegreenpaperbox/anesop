<script setup lang="ts">
import type { MenuIconName } from '../navigation'

defineProps<{ name: MenuIconName }>()

/** 内联 SVG：避免为 3 个图标引入图标库依赖（allowlist 未登记）。 */
const paths: Record<MenuIconName, string[]> = {
  home: [
    'M3.5 10.5 12 3.5l8.5 7',
    'M5.5 9.5V20a.5.5 0 0 0 .5.5h12a.5.5 0 0 0 .5-.5V9.5',
    'M9.5 20.5v-6h5v6',
  ],
  sessions: ['M3.5 5.5h17v11h-17z', 'M8 20.5h8', 'M12 16.5v4', 'M7.5 9h3', 'M7.5 12.5h6'],
  lock: ['M6.5 10.5h11v9h-11z', 'M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5', 'M12 14v2.5'],
}
</script>

<template>
  <svg
    class="menu-icon"
    viewBox="0 0 24 24"
    width="18"
    height="18"
    fill="none"
    stroke="currentColor"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    <path v-for="path in paths[name]" :key="path" :d="path" />
  </svg>
</template>

<style scoped>
.menu-icon {
  flex: 0 0 auto;
}
</style>
