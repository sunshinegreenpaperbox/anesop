<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

interface Crumb {
  title: string
  path?: string
}

const route = useRoute()

const crumbs = computed<Crumb[]>(() => {
  const matched = route.matched
    .filter((record) => typeof record.meta.title === 'string' && record.meta.menu !== false)
    .map((record) => ({
      title: record.meta.title as string,
      path: record.path,
    }))

  if (matched.length === 0) return [{ title: '首页', path: '/home' }]
  if (matched[0]?.title === '首页') return [{ title: '首页', path: '/home' }]
  return [{ title: '首页', path: '/home' }, ...matched]
})
</script>

<template>
  <el-breadcrumb class="app-breadcrumb" separator="/" aria-label="面包屑">
    <el-breadcrumb-item v-for="(crumb, index) in crumbs" :key="`${crumb.title}-${index}`">
      <RouterLink v-if="index < crumbs.length - 1 && crumb.path" :to="crumb.path">
        {{ crumb.title }}
      </RouterLink>
      <span v-else>{{ crumb.title }}</span>
    </el-breadcrumb-item>
  </el-breadcrumb>
</template>

<style scoped>
.app-breadcrumb {
  margin-bottom: var(--anesop-space-4);
  font-size: var(--anesop-font-size-sm);
}
</style>
