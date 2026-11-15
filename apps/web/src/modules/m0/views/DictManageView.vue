<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '../../../api/http'
import {
  createDictItem,
  disableDictItem,
  listDictItems,
  listDictTypes,
  updateDictItem,
  type DictItem,
  type DictType,
} from '../api/platform'
import { useAuthStore } from '../../../stores/auth'

/**
 * 字典管理（M8-M0-04，契约 platform.yaml 0.2.0）。
 *
 * - 字典全院可读（m0:dict:read），维护需要 m0:dict:write（V27 仅授予 SYS_ADMIN）；
 * - 编码创建后不可修改（历史记录按编码解释语义）；停用为逻辑停用（enabled=false），
 *   不物理删除；
 * - includeDisabled 仅管理员可见；字典只放口径说明，禁止患者标识与临床自由文本。
 * - 编码重复 409、字段非法 400、越权 403 由后端固定文案，页面只展示 message/traceId。
 */

const auth = useAuthStore()

const types = ref<DictType[]>([])
const selectedType = ref('')
const items = ref<DictItem[]>([])
const includeDisabled = ref(false)
const loadingTypes = ref(false)
const loadingItems = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)

const form = reactive({
  code: '',
  name: '',
  sort: 0,
  enabled: true,
  remark: '',
})

const canWrite = computed(() => auth.hasPermission('m0:dict:write'))
const isEditing = computed(() => editingId.value !== null)

function toApiError(cause: unknown): ApiError {
  if (cause instanceof ApiError) return cause
  return new ApiError({
    kind: 'network',
    code: 'UNKNOWN_ERROR',
    message: cause instanceof Error ? cause.message : '字典操作失败',
  })
}

function handleError(cause: unknown): void {
  error.value = toApiError(cause)
  ElMessage.error(error.value.message)
}

async function loadTypes(): Promise<void> {
  loadingTypes.value = true
  error.value = null
  try {
    const list = await listDictTypes()
    types.value = list.items
    if (!selectedType.value && list.items.length > 0) {
      selectedType.value = list.items[0]?.type ?? ''
    }
    await loadItems()
  } catch (cause) {
    handleError(cause)
  } finally {
    loadingTypes.value = false
  }
}

async function loadItems(): Promise<void> {
  if (!selectedType.value) {
    items.value = []
    return
  }
  loadingItems.value = true
  error.value = null
  try {
    const list = await listDictItems(selectedType.value, includeDisabled.value && canWrite.value)
    items.value = list.items
  } catch (cause) {
    handleError(cause)
    items.value = []
  } finally {
    loadingItems.value = false
  }
}

function resetForm(): void {
  form.code = ''
  form.name = ''
  form.sort = 0
  form.enabled = true
  form.remark = ''
}

function openCreate(): void {
  editingId.value = null
  resetForm()
  dialogVisible.value = true
}

function openEdit(item: DictItem): void {
  editingId.value = item.itemId
  form.code = item.code
  form.name = item.name
  form.sort = item.sort
  form.enabled = item.enabled
  form.remark = item.remark ?? ''
  dialogVisible.value = true
}

async function save(): Promise<void> {
  saving.value = true
  error.value = null
  try {
    if (editingId.value) {
      await updateDictItem(selectedType.value, editingId.value, {
        name: form.name.trim(),
        sort: form.sort,
        enabled: form.enabled,
        remark: form.remark.trim() || undefined,
      })
      ElMessage.success('字典项已更新')
    } else {
      await createDictItem(selectedType.value, {
        code: form.code.trim(),
        name: form.name.trim(),
        sort: form.sort,
        enabled: form.enabled,
        remark: form.remark.trim() || undefined,
      })
      ElMessage.success('字典项已新增')
    }
    dialogVisible.value = false
    await loadTypes()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

async function disable(item: DictItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认停用字典项「${item.name}」？停用为逻辑停用，历史记录仍按编码 ${item.code} 解释。`,
      '停用字典项',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await disableDictItem(selectedType.value, item.itemId)
    ElMessage.success('字典项已停用')
    await loadTypes()
  } catch (cause) {
    handleError(cause)
  }
}

onMounted(() => {
  void loadTypes()
})
</script>

<template>
  <section class="dicts">
    <header class="dicts__header">
      <div>
        <h1 class="dicts__title">字典管理</h1>
        <p class="dicts__subtitle">
          全院参考数据（性别/ASA/紧急度等）。查询需要 <code>m0:dict:read</code>，维护需要
          <code>m0:dict:write</code>（仅平台管理员）。编码创建后不可修改，停用为逻辑停用。
        </p>
      </div>
      <div class="dicts__actions">
        <el-select
          v-model="selectedType"
          class="dicts__select"
          placeholder="选择字典类型"
          :loading="loadingTypes"
          @change="loadItems"
        >
          <el-option
            v-for="type in types"
            :key="type.type"
            :label="`${type.name}（${type.type}，${type.itemCount} 项）`"
            :value="type.type"
          />
        </el-select>
        <el-checkbox
          v-if="canWrite"
          v-model="includeDisabled"
          label="含停用项"
          @change="loadItems"
        />
        <el-button v-if="canWrite" type="primary" @click="openCreate">新增字典项</el-button>
      </div>
    </header>

    <el-alert
      v-if="error"
      class="dicts__alert"
      type="error"
      :closable="false"
      show-icon
      title="字典操作失败"
    >
      <p class="dicts__error-message">{{ error.message }}</p>
      <p class="dicts__error-meta">
        错误码：{{ error.code }}
        <template v-if="error.traceId"> · 追踪号：{{ error.traceId }}</template>
      </p>
    </el-alert>

    <el-card class="dicts__card" shadow="never">
      <el-table v-loading="loadingItems" :data="items" stripe class="dicts__table">
        <el-table-column prop="code" label="编码" width="140" />
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column prop="sort" label="排序" width="90" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" effect="plain" size="small">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="remark"
          label="备注（口径说明）"
          min-width="200"
          show-overflow-tooltip
        />
        <el-table-column v-if="canWrite" label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.enabled" link type="danger" @click="disable(row)">停用</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <p class="dicts__empty">该类型暂无字典项。</p>
        </template>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="isEditing ? '编辑字典项' : '新增字典项'"
      width="520px"
    >
      <el-form label-width="90px">
        <el-form-item label="编码">
          <el-input v-model="form.code" :disabled="isEditing" placeholder="如 IV（创建后不可改）" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="中文显示名称（≤100）" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="9999" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="2"
            placeholder="口径说明（禁止患者标识与临床自由文本）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.dicts {
  display: flex;
  flex-direction: column;
  gap: var(--anesop-space-4);
}

.dicts__header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--anesop-space-3);
}

.dicts__title {
  margin: 0;
  font-size: var(--anesop-font-size-xl);
  font-weight: var(--anesop-font-weight-semibold);
}

.dicts__subtitle {
  margin: var(--anesop-space-1) 0 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
}

.dicts__subtitle code {
  padding: 0 var(--anesop-space-1);
  font-family: var(--anesop-font-family-mono);
  background: var(--anesop-color-primary-soft);
  border-radius: var(--anesop-radius-sm);
}

.dicts__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--anesop-space-2);
}

.dicts__select {
  width: 280px;
}

.dicts__alert {
  margin-bottom: var(--anesop-space-2);
}

.dicts__error-message {
  margin: 0;
  font-size: var(--anesop-font-size-sm);
}

.dicts__error-meta {
  margin: var(--anesop-space-1) 0 0;
  color: var(--anesop-color-text-secondary);
  font-family: var(--anesop-font-family-mono);
  font-size: var(--anesop-font-size-xs);
}

.dicts__card {
  border: 1px solid var(--anesop-color-border-light);
  border-radius: var(--anesop-radius-lg);
  box-shadow: var(--anesop-shadow-sm);
}

.dicts__empty {
  margin: 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
}
</style>
