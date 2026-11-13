<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

function goSchedule(): void {
  void router.push({ name: 'm1-schedule' })
}
</script>

<template>
  <section class="record-entry">
    <h1 class="record-entry__title">麻醉记录单</h1>
    <p class="record-entry__text">
      麻醉记录单按台次打开：在「排程工作台」确认台次标识后访问
      <code>/m2/records/:scheduleId</code>（排程页内的跳转按钮需 M1 写域任务接入，本任务写域 不含
      <code>modules/m1</code>）。一个台次只允许一张记录单，时间轴、用药与出入量
      append-only（不可改删），麻精类用药必须双人核对。
    </p>
    <el-button type="primary" data-testid="goto-schedule" @click="goSchedule">
      前往排程工作台
    </el-button>
  </section>
</template>

<style scoped>
.record-entry {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 12px;
  max-width: 720px;
}

.record-entry__title {
  margin: 0;
  font-size: 22px;
}

.record-entry__text {
  margin: 0;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}
</style>
