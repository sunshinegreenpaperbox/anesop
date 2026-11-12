<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <main class="status-page">
    <div class="status-page__card">
      <p class="status-page__code" aria-hidden="true">404</p>
      <h1 class="status-page__title">页面不存在</h1>
      <p class="status-page__desc">
        访问的地址不存在、已下线，或不属于当前版本的功能范围。请从侧边菜单进入已开放页面。
      </p>
      <p class="status-page__path">请求路径：{{ route.fullPath }}</p>
      <div class="status-page__actions">
        <RouterLink to="/home">
          <el-button type="primary">返回首页</el-button>
        </RouterLink>
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
  color: var(--anesop-color-primary);
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

.status-page__path {
  margin: 0 0 var(--anesop-space-4);
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
  word-break: break-all;
}

.status-page__actions {
  display: flex;
  justify-content: center;
}
</style>
