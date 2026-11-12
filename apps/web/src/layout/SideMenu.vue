<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useMenuStore } from '../stores/menu'
import MenuIcon from './MenuIcon.vue'

defineProps<{ collapsed: boolean }>()

const route = useRoute()
const menu = useMenuStore()
const activePath = computed(() => route.path)
</script>

<template>
  <nav class="side-menu" aria-label="主导航">
    <el-menu
      :default-active="activePath"
      :collapse="collapsed"
      :collapse-transition="false"
      router
      class="side-menu__list"
    >
      <el-menu-item v-for="item in menu.visibleMenus" :key="item.path" :index="item.path">
        <MenuIcon :name="item.icon" />
        <template #title>{{ item.title }}</template>
      </el-menu-item>
    </el-menu>
  </nav>
</template>

<style scoped>
.side-menu {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: var(--anesop-space-2) 0;
}

.side-menu__list {
  border-right: none;
  background: transparent;

  /* Element Plus 菜单颜色统一映射到设计令牌，页面不再写死。 */
  --el-menu-bg-color: transparent;
  --el-menu-text-color: var(--anesop-color-brand-100);
  --el-menu-hover-bg-color: var(--anesop-color-bg-inverse-hover);
  --el-menu-active-color: var(--anesop-color-text-inverse);
  --el-menu-item-height: 44px;
}

.side-menu__list :deep(.el-menu-item) {
  margin: var(--anesop-space-1) var(--anesop-space-2);
  border-radius: var(--anesop-radius-md);
  font-size: var(--anesop-font-size-base);
}

.side-menu__list :deep(.el-menu-item.is-active) {
  background: var(--anesop-color-primary);
  color: var(--anesop-color-text-inverse);
  font-weight: var(--anesop-font-weight-medium);
}

.side-menu__list :deep(.el-menu-item:focus-visible) {
  box-shadow: var(--anesop-shadow-focus);
}
</style>
