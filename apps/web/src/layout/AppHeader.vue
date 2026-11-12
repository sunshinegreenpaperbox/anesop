<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { labelForRole } from '../navigation'
import { useAuthStore } from '../stores/auth'

defineProps<{ collapsed: boolean }>()
const emit = defineEmits<{ toggle: [] }>()

const auth = useAuthStore()
const router = useRouter()

const roleText = computed(() => auth.roles.map(labelForRole))
const primaryRole = computed(() => roleText.value[0] ?? '未分配角色')
const initial = computed(() => auth.displayName.trim().charAt(0) || '用')

async function handleCommand(command: string): Promise<void> {
  if (command === 'logout') {
    await auth.logout()
    ElMessage.success('已退出登录')
    await router.replace({ name: 'login' })
    return
  }
  if (command === 'password') {
    await router.push({ name: 'm0-password' })
  }
}
</script>

<template>
  <header class="app-header">
    <div class="app-header__left">
      <button
        type="button"
        class="app-header__collapse"
        :aria-label="collapsed ? '展开侧边菜单' : '收起侧边菜单'"
        :aria-expanded="!collapsed"
        @click="emit('toggle')"
      >
        <svg
          viewBox="0 0 24 24"
          width="18"
          height="18"
          fill="none"
          stroke="currentColor"
          stroke-width="1.8"
          stroke-linecap="round"
          aria-hidden="true"
        >
          <path d="M4 6.5h16M4 12h16M4 17.5h16" />
        </svg>
      </button>
      <div class="app-header__titles">
        <span class="app-header__system">ANESOP 麻醉信息平台</span>
        <span class="app-header__subtitle">围术期全流程管理</span>
      </div>
    </div>

    <div class="app-header__right">
      <el-tag v-if="auth.mustChangePassword" type="warning" effect="plain" size="small">
        待修改初始口令
      </el-tag>
      <span class="app-header__role" :title="roleText.join('、')">{{ primaryRole }}</span>
      <el-dropdown trigger="click" @command="handleCommand">
        <button type="button" class="app-header__user" aria-label="用户菜单">
          <span class="app-header__avatar" aria-hidden="true">{{ initial }}</span>
          <span class="app-header__username">{{ auth.displayName }}</span>
          <span class="app-header__chevron" aria-hidden="true">▾</span>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item disabled>{{ auth.username }}</el-dropdown-item>
            <el-dropdown-item command="password">修改口令</el-dropdown-item>
            <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--anesop-space-4);
  flex: 0 0 auto;
  height: var(--anesop-layout-header-height);
  padding: 0 var(--anesop-space-6);
  background: var(--anesop-color-bg-card);
  border-bottom: 1px solid var(--anesop-color-border-light);
  box-shadow: var(--anesop-shadow-sm);
  z-index: var(--anesop-z-index-header);
}

.app-header__left,
.app-header__right {
  display: flex;
  align-items: center;
  gap: var(--anesop-space-3);
  min-width: 0;
}

.app-header__collapse {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  color: var(--anesop-color-text-regular);
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--anesop-radius-md);
  cursor: pointer;
}

.app-header__collapse:hover {
  color: var(--anesop-color-primary);
  background: var(--anesop-color-bg-hover);
}

.app-header__titles {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: var(--anesop-line-height-tight);
}

.app-header__system {
  overflow: hidden;
  color: var(--anesop-color-text-primary);
  font-size: var(--anesop-font-size-md);
  font-weight: var(--anesop-font-weight-semibold);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-header__subtitle {
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-xs);
}

.app-header__role {
  max-width: 240px;
  overflow: hidden;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-header__user {
  display: flex;
  align-items: center;
  gap: var(--anesop-space-2);
  padding: var(--anesop-space-1) var(--anesop-space-2);
  color: var(--anesop-color-text-primary);
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--anesop-radius-full);
  cursor: pointer;
}

.app-header__user:hover {
  background: var(--anesop-color-bg-hover);
}

.app-header__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  color: var(--anesop-color-text-inverse);
  font-size: var(--anesop-font-size-sm);
  font-weight: var(--anesop-font-weight-semibold);
  background: var(--anesop-color-primary);
  border-radius: var(--anesop-radius-full);
}

.app-header__username {
  max-width: 160px;
  overflow: hidden;
  font-size: var(--anesop-font-size-base);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-header__chevron {
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-xs);
}

@media (max-width: 900px) {
  .app-header {
    padding: 0 var(--anesop-space-4);
  }

  .app-header__subtitle,
  .app-header__role {
    display: none;
  }
}
</style>
