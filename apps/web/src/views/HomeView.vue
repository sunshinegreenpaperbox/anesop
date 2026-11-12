<script setup lang="ts">
import { computed } from 'vue'
import { getApiBaseUrl } from '../api/http'
import { getSessionExpiresAt } from '../api/session'
import { labelForRole } from '../navigation'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()

const moduleLabels: Record<string, string> = {
  m0: 'M0 公共支撑',
  m1: 'M1 手术排程',
  m2: 'M2 麻醉信息',
  m3: 'M3 人员管理',
  m4: 'M4 耗材仓库',
  m5: 'M5 药品统计',
  m6: 'M6 质控统计',
  m7: 'M7 绩效',
  m8: 'M8 大屏',
  auth: '历史认证权限点',
}

const scopeLabels: Record<string, string> = {
  ALL: '全院',
  SITE: '院区',
  DEPARTMENT: '科室',
  ROOM: '术间',
  OWN_PATIENTS: '本人患者',
}

const roleText = computed(() => auth.roles.map(labelForRole))

const permissionGroups = computed(() => {
  const groups = new Map<string, string[]>()
  for (const code of auth.permissions) {
    const module = code.split(':')[0] ?? 'other'
    const list = groups.get(module) ?? []
    list.push(code)
    groups.set(module, list)
  }
  return [...groups.entries()].map(([module, codes]) => ({
    module,
    label: moduleLabels[module] ?? module,
    codes,
  }))
})

const scopeText = computed(() =>
  auth.dataScopes.length === 0
    ? ['未配置数据范围']
    : auth.dataScopes.map((scope) => {
        const label = scopeLabels[scope.type] ?? scope.type
        return scope.type === 'ALL' ? label : `${label}（${scope.scopeIds.length} 项）`
      }),
)

const expiresText = computed(() => {
  const expiresAt = getSessionExpiresAt()
  if (!expiresAt) return '—'
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(expiresAt),
  )
})

const systemStatus = computed(() => [
  { label: '前端壳', value: 'S0-08（登录 / 布局 / 路由守卫 / 令牌刷新）' },
  { label: 'API 基址', value: getApiBaseUrl() },
  { label: '会话状态', value: auth.isAuthenticated ? '已认证' : '未认证' },
  { label: '访问令牌到期', value: expiresText.value },
])
</script>

<template>
  <section class="home">
    <header class="home__hero">
      <div>
        <p class="home__eyebrow">围术期全流程管理工作台</p>
        <h1 class="home__title">欢迎回来，{{ auth.displayName || '使用者' }}</h1>
        <p class="home__subtitle">
          当前共 {{ auth.permissions.length }} 个权限点。菜单与页面访问均由
          <code>/v1/auth/me</code> 返回的权限快照驱动。
        </p>
      </div>
      <el-tag v-if="auth.mustChangePassword" type="warning" size="large" effect="dark">
        首次登录，请先修改初始口令
      </el-tag>
    </header>

    <div class="home__grid">
      <el-card class="home-card" shadow="never">
        <template #header>
          <h2 class="home-card__title">当前角色</h2>
        </template>
        <div v-if="roleText.length > 0" class="home-card__tags">
          <el-tag v-for="role in roleText" :key="role" type="primary" effect="plain">
            {{ role }}
          </el-tag>
        </div>
        <p v-else class="home-card__empty">尚未分配角色，请联系信息科管理员。</p>

        <h3 class="home-card__subtitle">数据范围</h3>
        <div class="home-card__tags">
          <el-tag v-for="scope in scopeText" :key="scope" type="info" effect="plain">
            {{ scope }}
          </el-tag>
        </div>
      </el-card>

      <el-card class="home-card" shadow="never">
        <template #header>
          <h2 class="home-card__title">权限概览</h2>
        </template>
        <p class="home-card__summary">
          共 <strong>{{ auth.permissions.length }}</strong> 个权限点，覆盖
          <strong>{{ permissionGroups.length }}</strong> 个模块。
        </p>
        <div v-if="permissionGroups.length > 0" class="home-card__groups">
          <div v-for="group in permissionGroups" :key="group.module" class="home-card__group">
            <span class="home-card__group-label">{{ group.label }}</span>
            <div class="home-card__tags">
              <el-tag v-for="code in group.codes" :key="code" size="small" effect="plain">
                {{ code }}
              </el-tag>
            </div>
          </div>
        </div>
        <p v-else class="home-card__empty">当前账号没有可用权限点。</p>
      </el-card>

      <el-card class="home-card" shadow="never">
        <template #header>
          <h2 class="home-card__title">系统状态</h2>
        </template>
        <dl class="home-card__status">
          <div v-for="item in systemStatus" :key="item.label" class="home-card__status-row">
            <dt>{{ item.label }}</dt>
            <dd>{{ item.value }}</dd>
          </div>
        </dl>
        <p class="home-card__note">
          ECharts 图表与业务指标延后到 M8；本页只展示登录态与权限快照，不调用未在契约登记的接口。
        </p>
      </el-card>
    </div>
  </section>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: var(--anesop-space-6);
}

.home__hero {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--anesop-space-4);
  padding: var(--anesop-space-6);
  color: var(--anesop-color-text-inverse);
  background: var(--anesop-gradient-brand);
  border-radius: var(--anesop-radius-xl);
  box-shadow: var(--anesop-shadow-md);
}

.home__eyebrow {
  margin: 0 0 var(--anesop-space-2);
  color: var(--anesop-color-brand-100);
  font-size: var(--anesop-font-size-sm);
}

.home__title {
  margin: 0;
  font-size: var(--anesop-font-size-2xl);
  font-weight: var(--anesop-font-weight-semibold);
  line-height: var(--anesop-line-height-tight);
}

.home__subtitle {
  max-width: 720px;
  margin: var(--anesop-space-2) 0 0;
  color: var(--anesop-color-brand-50);
  font-size: var(--anesop-font-size-base);
}

.home__subtitle code {
  padding: 0 var(--anesop-space-1);
  font-family: var(--anesop-font-family-mono);
  font-size: var(--anesop-font-size-sm);
  background: var(--anesop-color-inverse-overlay-soft);
  border-radius: var(--anesop-radius-sm);
}

.home__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: var(--anesop-space-4);
  align-items: start;
}

.home-card {
  border: 1px solid var(--anesop-color-border-light);
  border-radius: var(--anesop-radius-lg);
  box-shadow: var(--anesop-shadow-sm);
}

.home-card :deep(.el-card__header) {
  padding: var(--anesop-space-3) var(--anesop-space-4);
  border-bottom-color: var(--anesop-color-border-light);
}

.home-card :deep(.el-card__body) {
  padding: var(--anesop-space-4);
}

.home-card__title {
  margin: 0;
  color: var(--anesop-color-text-primary);
  font-size: var(--anesop-font-size-md);
  font-weight: var(--anesop-font-weight-semibold);
}

.home-card__subtitle {
  margin: var(--anesop-space-4) 0 var(--anesop-space-2);
  color: var(--anesop-color-text-regular);
  font-size: var(--anesop-font-size-sm);
  font-weight: var(--anesop-font-weight-medium);
}

.home-card__tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--anesop-space-2);
}

.home-card__summary {
  margin: 0 0 var(--anesop-space-3);
  color: var(--anesop-color-text-regular);
  font-size: var(--anesop-font-size-base);
}

.home-card__summary strong {
  color: var(--anesop-color-primary);
}

.home-card__groups {
  display: flex;
  flex-direction: column;
  gap: var(--anesop-space-3);
}

.home-card__group {
  display: flex;
  gap: var(--anesop-space-2);
  align-items: flex-start;
}

.home-card__group-label {
  flex: 0 0 96px;
  padding-top: var(--anesop-space-1);
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-xs);
}

.home-card__group .home-card__tags {
  flex: 1 1 auto;
  min-width: 0;
}

.home-card__empty {
  margin: 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
}

.home-card__status {
  display: flex;
  flex-direction: column;
  gap: var(--anesop-space-2);
  margin: 0;
}

.home-card__status-row {
  display: flex;
  gap: var(--anesop-space-3);
  align-items: baseline;
  justify-content: space-between;
  padding-bottom: var(--anesop-space-2);
  border-bottom: 1px dashed var(--anesop-color-border-light);
}

.home-card__status-row dt {
  flex: 0 0 auto;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
}

.home-card__status-row dd {
  margin: 0;
  color: var(--anesop-color-text-primary);
  font-size: var(--anesop-font-size-sm);
  text-align: right;
}

.home-card__note {
  margin: var(--anesop-space-4) 0 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-xs);
  line-height: var(--anesop-line-height-relaxed);
}
</style>
