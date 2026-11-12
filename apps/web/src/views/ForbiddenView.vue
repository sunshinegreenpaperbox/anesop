<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const fromPath = computed(() => (typeof route.query.from === 'string' ? route.query.from : ''))

async function backToHome(): Promise<void> {
  await router.replace('/home')
}

async function switchAccount(): Promise<void> {
  await auth.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <main class="status-page">
    <div class="status-page__card">
      <p class="status-page__code" aria-hidden="true">403</p>
      <h1 class="status-page__title">无权访问该页面</h1>
      <p class="status-page__desc">
        当前账号缺少访问所需权限点，或数据范围不足。权限由信息科按岗位分配，变更请走权限申请流程。
      </p>
      <p v-if="fromPath" class="status-page__path">目标页面：{{ fromPath }}</p>
      <p class="status-page__meta">
        当前账号：{{ auth.username || '—' }} · 角色：{{ auth.roles.join('、') || '未分配' }}
      </p>
      <div class="status-page__actions">
        <el-button type="primary" @click="backToHome">返回首页</el-button>
        <el-button @click="switchAccount">切换账号</el-button>
      </div>
    </div>
  </main>
</template>

<style scoped>
.status-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: var(--anesop-space-6);
  background: var(--anesop-color-bg-page);
}

.status-page__card {
  width: 100%;
  max-width: 560px;
  padding: var(--anesop-space-8);
  text-align: center;
  background: var(--anesop-color-bg-card);
  border: 1px solid var(--anesop-color-border-light);
  border-radius: var(--anesop-radius-xl);
  box-shadow: var(--anesop-shadow-md);
}

.status-page__code {
  margin: 0;
  color: var(--anesop-color-warning);
  font-family: var(--anesop-font-family-number);
  font-size: var(--anesop-font-size-4xl);
  font-weight: var(--anesop-font-weight-bold);
  letter-spacing: 0.08em;
}

.status-page__title {
  margin: var(--anesop-space-2) 0;
  font-size: var(--anesop-font-size-xl);
  font-weight: var(--anesop-font-weight-semibold);
}

.status-page__desc {
  margin: 0 0 var(--anesop-space-3);
  color: var(--anesop-color-text-regular);
  line-height: var(--anesop-line-height-relaxed);
}

.status-page__path,
.status-page__meta {
  margin: 0 0 var(--anesop-space-2);
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
  word-break: break-all;
}

.status-page__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--anesop-space-2);
  justify-content: center;
  margin-top: var(--anesop-space-4);
}
</style>
