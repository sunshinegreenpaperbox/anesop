<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

function goApplications(): void {
  void router.push({ name: 'm1-applications' })
}
</script>

<template>
  <section class="preop-entry">
    <h1 class="preop-entry__title">术前访视与评估</h1>
    <p class="preop-entry__text">
      术前访视从手术申请进入：请在「手术申请」列表或详情页选择患者后打开访视页。 页面路径为
      <code>/m2/preop/:applicationId</code>。
    </p>
    <el-button type="primary" data-testid="goto-applications" @click="goApplications">
      前往手术申请
    </el-button>
  </section>
</template>

<style scoped>
.preop-entry {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 12px;
  max-width: 640px;
}

.preop-entry__title {
  margin: 0;
  font-size: 22px;
}

.preop-entry__text {
  margin: 0;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}
</style>
