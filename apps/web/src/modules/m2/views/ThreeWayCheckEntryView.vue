<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

function goApplications(): void {
  void router.push({ name: 'm1-applications' })
}
</script>

<template>
  <section class="threeway-entry">
    <h1 class="threeway-entry__title">三方核查</h1>
    <p class="threeway-entry__text">
      三方核查从手术申请进入：请在「手术申请」列表或详情页选择患者后打开核查页。页面路径为
      <code>/m2/three-way/:applicationId</code>。
    </p>
    <el-button type="primary" data-testid="goto-applications" @click="goApplications">
      前往手术申请
    </el-button>
  </section>
</template>

<style scoped>
.threeway-entry {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 12px;
  max-width: 640px;
}

.threeway-entry__title {
  margin: 0;
  font-size: 22px;
}

.threeway-entry__text {
  margin: 0;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}
</style>
