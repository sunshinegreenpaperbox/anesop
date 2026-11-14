<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'
import {
  createMaterial,
  listMaterials,
  materialStatusLabels,
  updateMaterial,
  updateMaterialStatus,
  type CreateMaterialRequest,
  type Material,
  type UpdateMaterialRequest,
} from '../api/material'

/**
 * 耗材目录维护（M4-M14-01，契约 material.yaml 0.1.0）。
 *
 * - 目录全院可读（m4:material:read），维护需要 m4:material:write；
 * - 编码/名称/UDI-DI 唯一（冲突 409），更新使用 version 乐观锁（过期 409）；
 * - highValue=true 表示必须逐件 UDI 登记/使用；有库存时不能直接改为高值（409）；
 * - 页面不把高值/效期标志当作临床建议，仅维护主数据。
 */

const auth = useAuthStore()

const materials = ref<Material[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)
const keyword = ref('')
const status = ref('')
const highValue = ref<'' | 'true' | 'false'>('')
const total = ref(0)
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)

const form = reactive({
  code: '',
  name: '',
  specification: '',
  unit: '',
  category: 'CATHETER',
  udiDi: '',
  highValue: false,
  expiryManaged: true,
  remark: '',
  version: 1,
})

const canWrite = computed(() => auth.hasPermission('m4:material:write'))
const isEditing = computed(() => editingId.value !== null)
const errorTitle = computed(() => {
  if (!error.value) return ''
  if (error.value.status === 403)
    return '无权访问耗材目录（需要 m4:material:read / m4:material:write）'
  if (error.value.status === 409)
    return '耗材编码/名称/UDI-DI 重复、库存未清零或已被他人修改，请刷新后重试'
  if (error.value.status === 400) return '耗材字段校验失败（见下发明细）'
  return '耗材目录操作失败'
})

function handleError(cause: unknown): void {
  error.value = cause instanceof ApiError ? cause : null
  ElMessage.error(cause instanceof ApiError ? cause.message : '请求失败，请稍后重试')
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const list = await listMaterials({
      keyword: keyword.value.trim(),
      status: status.value,
      highValue: highValue.value === '' ? undefined : highValue.value === 'true',
      size: 50,
    })
    materials.value = list.items
    total.value = list.total
  } catch (cause) {
    handleError(cause)
  } finally {
    loading.value = false
  }
}

function resetForm(): void {
  form.code = ''
  form.name = ''
  form.specification = ''
  form.unit = ''
  form.category = 'CATHETER'
  form.udiDi = ''
  form.highValue = false
  form.expiryManaged = true
  form.remark = ''
  form.version = 1
}

function openCreate(): void {
  editingId.value = null
  resetForm()
  dialogVisible.value = true
}

function openEdit(material: Material): void {
  editingId.value = material.materialId
  form.code = material.code
  form.name = material.name
  form.specification = material.specification
  form.unit = material.unit
  form.category = material.category
  form.udiDi = material.udiDi ?? ''
  form.highValue = material.highValue
  form.expiryManaged = material.expiryManaged
  form.remark = material.remark ?? ''
  form.version = material.version
  dialogVisible.value = true
}

async function toggleStatus(material: Material): Promise<void> {
  const target = material.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  try {
    await updateMaterialStatus(material.materialId, target)
    ElMessage.success(target === 'ACTIVE' ? '耗材已启用' : '耗材已停用（历史库存与追溯保留）')
    await load()
  } catch (cause) {
    handleError(cause)
  }
}

async function submit(): Promise<void> {
  if (!form.name.trim() || !form.specification.trim() || !form.unit.trim()) {
    ElMessage.warning('名称、规格与单位不能为空')
    return
  }
  if (!isEditing.value && !/^[A-Za-z0-9._-]{1,64}$/.test(form.code.trim())) {
    ElMessage.warning('耗材编码只能包含字母、数字、点、下划线与连字符（1–64）')
    return
  }
  saving.value = true
  error.value = null
  try {
    const udiDi = form.udiDi.trim() || undefined
    if (isEditing.value && editingId.value) {
      const payload: UpdateMaterialRequest = {
        name: form.name.trim(),
        specification: form.specification.trim(),
        unit: form.unit.trim(),
        category: form.category,
        udiDi,
        highValue: form.highValue,
        expiryManaged: form.expiryManaged,
        remark: form.remark.trim() || undefined,
        version: form.version,
      }
      await updateMaterial(editingId.value, payload)
      ElMessage.success('耗材已更新（版本 +1）')
    } else {
      const payload: CreateMaterialRequest = {
        code: form.code.trim(),
        name: form.name.trim(),
        specification: form.specification.trim(),
        unit: form.unit.trim(),
        category: form.category,
        udiDi,
        highValue: form.highValue,
        expiryManaged: form.expiryManaged,
        remark: form.remark.trim() || undefined,
      }
      await createMaterial(payload)
      ElMessage.success('耗材已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="material-catalog">
    <header class="page-header">
      <div>
        <h1>耗材目录</h1>
        <p class="hint">
          耗材主数据（编码/名称/UDI-DI 唯一）；高值耗材必须逐件 UDI
          登记与使用，效期管理耗材出入库校验批次与效期。
        </p>
      </div>
      <div class="actions">
        <el-input
          v-model="keyword"
          data-testid="material-keyword"
          placeholder="编码/名称/UDI-DI"
          clearable
          style="width: 220px"
          @keyup.enter="load"
        />
        <el-select
          v-model="status"
          data-testid="material-status"
          style="width: 120px"
          @change="load"
        >
          <el-option label="全部状态" value="" />
          <el-option label="启用" value="ACTIVE" />
          <el-option label="停用" value="INACTIVE" />
        </el-select>
        <el-select
          v-model="highValue"
          data-testid="material-high-value"
          style="width: 130px"
          @change="load"
        >
          <el-option label="全部耗材" value="" />
          <el-option label="仅高值" value="true" />
          <el-option label="非高值" value="false" />
        </el-select>
        <el-button data-testid="material-refresh" @click="load">查询</el-button>
        <el-button v-if="canWrite" type="primary" data-testid="material-create" @click="openCreate">
          新建耗材
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="error"
      type="error"
      :title="errorTitle"
      :description="`${error.message}${error.traceId ? `（traceId: ${error.traceId}）` : ''}`"
      show-icon
      :closable="false"
      data-testid="material-error"
    />

    <el-table
      v-loading="loading"
      :data="materials"
      data-testid="material-table"
      border
      style="width: 100%"
    >
      <el-table-column prop="code" label="编码" width="150" />
      <el-table-column prop="name" label="名称" min-width="170" />
      <el-table-column prop="specification" label="规格" width="120" />
      <el-table-column prop="unit" label="单位" width="70" />
      <el-table-column prop="udiDi" label="UDI-DI" width="150" />
      <el-table-column label="高值" width="80">
        <template #default="{ row }">
          <el-tag :type="row.highValue ? 'danger' : 'info'" size="small">
            {{ row.highValue ? '高值' : '—' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="效期管理" width="90">
        <template #default="{ row }">
          {{ row.expiryManaged ? '是' : '否' }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          {{ materialStatusLabels[row.status] ?? row.status }}
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="70" />
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button
            v-if="canWrite"
            link
            type="primary"
            data-testid="material-edit"
            @click="openEdit(row)"
          >
            编辑
          </el-button>
          <el-button
            v-if="canWrite"
            link
            :type="row.status === 'ACTIVE' ? 'warning' : 'success'"
            data-testid="material-toggle-status"
            @click="toggleStatus(row)"
          >
            {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <span data-testid="material-empty">暂无耗材</span>
      </template>
    </el-table>
    <p class="hint" data-testid="material-total">共 {{ total }} 条</p>

    <el-dialog v-model="dialogVisible" :title="isEditing ? '编辑耗材' : '新建耗材'" width="560px">
      <el-form label-width="100px" data-testid="material-form">
        <el-form-item label="编码">
          <el-input v-model="form.code" data-testid="material-code" :disabled="isEditing" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" data-testid="material-name" />
        </el-form-item>
        <el-form-item label="规格">
          <el-input v-model="form.specification" data-testid="material-spec" />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="form.unit" data-testid="material-unit" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="form.category" data-testid="material-category" />
        </el-form-item>
        <el-form-item label="UDI-DI">
          <el-input v-model="form.udiDi" data-testid="material-udi-di" placeholder="可选；唯一" />
        </el-form-item>
        <el-form-item label="高值耗材">
          <el-switch v-model="form.highValue" data-testid="material-high-value-switch" />
        </el-form-item>
        <el-form-item label="效期管理">
          <el-switch v-model="form.expiryManaged" data-testid="material-expiry-switch" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" data-testid="material-remark" type="textarea" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="material-submit" @click="submit">
          保存
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.material-catalog {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
}
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 4px 0 0;
}
</style>
