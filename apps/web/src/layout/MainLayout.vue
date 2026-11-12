<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import AppBreadcrumb from './AppBreadcrumb.vue'
import AppHeader from './AppHeader.vue'
import SideMenu from './SideMenu.vue'

/**
 * 主壳布局：左侧导航 + 顶栏 + 面包屑 + 内容区。
 * 1366/1920 宽屏默认展开；<1200 自动收起为图标栏，也可手动切换。
 */
const collapsed = ref(false)

function syncForViewport(): void {
  collapsed.value = window.innerWidth < 1200
}

onMounted(() => {
  syncForViewport()
  window.addEventListener('resize', syncForViewport)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', syncForViewport)
})

function toggleSidebar(): void {
  collapsed.value = !collapsed.value
}
</script>

<template>
  <div class="app-layout" :class="{ 'is-collapsed': collapsed }">
    <aside class="app-layout__sidebar">
      <div class="app-layout__brand">
        <span class="app-layout__brand-mark" aria-hidden="true">A</span>
        <span v-if="!collapsed" class="app-layout__brand-text">
          <span class="app-layout__brand-name">ANESOP</span>
          <span class="app-layout__brand-sub">麻醉信息平台</span>
        </span>
      </div>
      <SideMenu :collapsed="collapsed" />
      <div v-if="!collapsed" class="app-layout__version">S0-08 前端壳</div>
    </aside>

    <div class="app-layout__main">
      <AppHeader :collapsed="collapsed" @toggle="toggleSidebar" />
      <main class="app-layout__content" tabindex="-1">
        <AppBreadcrumb />
        <div class="app-layout__page">
          <RouterView />
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--anesop-color-bg-page);
}

.app-layout__sidebar {
  display: flex;
  flex: 0 0 var(--anesop-layout-sidebar-width);
  flex-direction: column;
  width: var(--anesop-layout-sidebar-width);
  color: var(--anesop-color-text-inverse);
  background: var(--anesop-color-bg-inverse);
  transition: width 160ms ease;
}

.app-layout.is-collapsed .app-layout__sidebar {
  flex-basis: var(--anesop-layout-sidebar-collapsed-width);
  width: var(--anesop-layout-sidebar-collapsed-width);
}

.app-layout__brand {
  display: flex;
  align-items: center;
  gap: var(--anesop-space-3);
  height: var(--anesop-layout-header-height);
  padding: 0 var(--anesop-space-4);
  border-bottom: 1px solid var(--anesop-color-bg-inverse-hover);
}

.app-layout__brand-mark {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  color: var(--anesop-color-text-inverse);
  font-size: var(--anesop-font-size-lg);
  font-weight: var(--anesop-font-weight-bold);
  background: var(--anesop-color-primary);
  border-radius: var(--anesop-radius-lg);
}

.app-layout__brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: var(--anesop-line-height-tight);
}

.app-layout__brand-name {
  font-size: var(--anesop-font-size-md);
  font-weight: var(--anesop-font-weight-bold);
  letter-spacing: 0.04em;
}

.app-layout__brand-sub {
  color: var(--anesop-color-brand-100);
  font-size: var(--anesop-font-size-xs);
}

.app-layout__version {
  padding: var(--anesop-space-3) var(--anesop-space-4);
  color: var(--anesop-color-brand-200);
  font-size: var(--anesop-font-size-xs);
  border-top: 1px solid var(--anesop-color-bg-inverse-hover);
}

.app-layout__main {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  min-width: 0;
}

.app-layout__content {
  flex: 1 1 auto;
  min-height: 0;
  padding: var(--anesop-space-6);
  overflow-y: auto;
}

.app-layout__page {
  width: 100%;
  max-width: var(--anesop-layout-content-max-width);
  margin: 0 auto;
}

@media (max-width: 900px) {
  .app-layout__content {
    padding: var(--anesop-space-4);
  }
}
</style>
