<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useMenuStore } from '../stores/menu'
import { useMessageStore } from '../stores/message'
import MenuIcon from './MenuIcon.vue'

defineProps<{ collapsed: boolean }>()

const route = useRoute()
const menu = useMenuStore()
const message = useMessageStore()
const activePath = computed(() => route.path)
const unreadBadge = computed(() => (message.unreadCount > 99 ? '99+' : String(message.unreadCount)))

onMounted(() => {
  void message.refresh()
})
// 每次导航刷新角标（轻量 1 条分页请求），保证在其他页面也能看到最新未读数。
watch(
  () => route.path,
  () => {
    void message.refresh()
  },
)
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
        <template #title>
          <span class="side-menu__label">{{ item.title }}</span>
          <el-badge
            v-if="item.path === '/m0/messages' && message.unreadCount > 0"
            :value="unreadBadge"
            :max="99"
            class="side-menu__badge"
            data-testid="nav-unread-badge"
          />
        </template>
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

.side-menu__label {
  flex: 1 1 auto;
}

.side-menu__badge {
  margin-left: var(--anesop-space-2);
}

.side-menu__badge :deep(.el-badge__content) {
  border: none;
}
</style>
