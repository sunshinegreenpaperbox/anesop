<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'
import {
  createDrug,
  drugStatusLabels,
  listDrugs,
  updateDrug,
  type CreateDrugRequest,
  type Drug,
  type UpdateDrugRequest,
} from '../api/m4'

/**
 * 药品目录维护（M4-M15-01，契约 drug.yaml 0.1.0）。
 *
 * - 目录全院可读（m4:drug:read），维护需要 m4:drug:write（V20 授予 SYS_ADMIN/SYS_PHARMACIST）；
 * - 编码/名称唯一（重复 409）、别名全局唯一且不得与既有名称冲突（400）、更新使用 version
 *   乐观锁（过期 409）；
 * - 麻精标志（narcotic）是麻醉记录麻精判定的唯一权威（T-4），变更需谨慎并在审计留痕；
 *   页面不把该标志作为临床建议，仅维护主数据。
 */

const auth = useAuthStore()

const drugs = ref<Drug[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)
const keyword = ref('')
const status = ref('')
const total = ref(0)
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)

const form = reactive({
  code: '',
  name: '',
  genericName: '',
  specification: '',
  unit: '',
  dosageForm: 'INJECTION',
  category: 'OPIOID',
  narcotic: false,
  highRisk: false,
  aliasesText: '',
  remark: '',
  version: 1,
})

const canWrite = computed(() => auth.hasPermission('m4:drug:write'))
const errorTitle = computed(() => {
  if (!error.value) return ''
  if (error.value.status === 403) return '无权访问药品目录（需要 m4:drug:read / m4:drug:write）'
  if (error.value.status === 409) return '药品编码/名称重复或已被他人修改，请刷新后重试'
  if (error.value.status === 400) return '药品字段校验失败（见下发明细）'
  return '药品目录操作失败'
})
const isEditing = computed(() => editingId.value !== null)

function handleError(cause: unknown): void {
  error.value = cause instanceof ApiError ? cause : null
  ElMessage.error(cause instanceof ApiError ? cause.message : '请求失败，请稍后重试')
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const list = await listDrugs({ keyword: keyword.value.trim(), status: status.value, size: 50 })
    drugs.value = list.items
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
  form.genericName = ''
  form.specification = ''
  form.unit = ''
  form.dosageForm = 'INJECTION'
  form.category = 'OPIOID'
  form.narcotic = false
  form.highRisk = false
  form.aliasesText = ''
  form.remark = ''
  form.version = 1
}

function openCreate(): void {
  editingId.value = null
  resetForm()
  dialogVisible.value = true
}

function openEdit(drug: Drug): void {
  editingId.value = drug.drugId
  form.code = drug.code
  form.name = drug.name
  form.genericName = drug.genericName ?? ''
  form.specification = drug.specification
  form.unit = drug.unit
  form.dosageForm = drug.dosageForm
  form.category = drug.category
  form.narcotic = drug.narcotic
  form.highRisk = drug.highRisk
  form.aliasesText = drug.aliases.join('，')
  form.remark = drug.remark ?? ''
  form.version = drug.version
  dialogVisible.value = true
}

function parseAliases(): string[] {
  return form.aliasesText
    .split(/[，,]/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

async function submit(): Promise<void> {
  if (!form.name.trim() || !form.specification.trim() || !form.unit.trim()) {
    ElMessage.warning('名称、规格与单位不能为空')
    return
  }
  if (!isEditing.value && !/^[A-Za-z0-9._-]{1,64}$/.test(form.code.trim())) {
    ElMessage.warning('药品编码只能包含字母、数字、点、下划线与连字符（1–64）')
    return
  }
  saving.value = true
  error.value = null
  try {
    const aliases = parseAliases()
    if (isEditing.value && editingId.value) {
      const payload: UpdateDrugRequest = {
        name: form.name.trim(),
        genericName: form.genericName.trim() || undefined,
        specification: form.specification.trim(),
        unit: form.unit.trim(),
        dosageForm: form.dosageForm,
        category: form.category,
        narcotic: form.narcotic,
        highRisk: form.highRisk,
        aliases,
        remark: form.remark.trim() || undefined,
        version: form.version,
      }
      await updateDrug(editingId.value, payload)
      ElMessage.success('药品已更新（版本 +1）')
    } else {
      const payload: CreateDrugRequest = {
        code: form.code.trim(),
        name: form.name.trim(),
        genericName: form.genericName.trim() || undefined,
        specification: form.specification.trim(),
        unit: form.unit.trim(),
        dosageForm: form.dosageForm,
        category: form.category,
        narcotic: form.narcotic,
        highRisk: form.highRisk,
        aliases,
        remark: form.remark.trim() || undefined,
      }
      await createDrug(payload)
      ElMessage.success('药品已创建')
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
  <section class="drug-catalog">
    <header class="page-header">
      <div>
        <h1>药品目录</h1>
        <p class="hint">
          药品主数据（编码/名称/别名唯一）；麻精标志由药房/管理员维护，是麻醉记录麻精判定的唯一权威。
        </p>
      </div>
      <div class="actions">
        <el-input
          v-model="keyword"
          data-testid="drug-keyword"
          placeholder="编码/名称/通用名/别名"
          clearable
          style="width: 240px"
          @keyup.enter="load"
        />
        <el-select v-model="status" data-testid="drug-status" style="width: 120px" @change="load">
          <el-option label="全部状态" value="" />
          <el-option label="启用" value="ACTIVE" />
          <el-option label="停用" value="INACTIVE" />
        </el-select>
        <el-button data-testid="drug-refresh" @click="load">查询</el-button>
        <el-button v-if="canWrite" type="primary" data-testid="drug-create" @click="openCreate">
          新建药品
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
      data-testid="drug-error"
    />

    <el-table v-loading="loading" :data="drugs" data-testid="drug-table" border style="width: 100%">
      <el-table-column prop="code" label="编码" width="150" />
      <el-table-column prop="name" label="名称" min-width="170" />
      <el-table-column prop="genericName" label="通用名" min-width="170" />
      <el-table-column prop="specification" label="规格" width="120" />
      <el-table-column prop="unit" label="单位" width="70" />
      <el-table-column label="麻精" width="70">
        <template #default="{ row }">
          <el-tag :type="row.narcotic ? 'danger' : 'info'" size="small">
            {{ row.narcotic ? '麻精' : '普通' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="高危" width="70">
        <template #default="{ row }">
          <el-tag :type="row.highRisk ? 'warning' : 'info'" size="small">
            {{ row.highRisk ? '高危' : '—' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          {{ drugStatusLabels[row.status] ?? row.status }}
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="70" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button
            v-if="canWrite"
            link
            type="primary"
            data-testid="drug-edit"
            @click="openEdit(row)"
          >
            编辑
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <span data-testid="drug-empty">暂无药品</span>
      </template>
    </el-table>
    <p class="hint" data-testid="drug-total">共 {{ total }} 条</p>

    <el-dialog v-model="dialogVisible" :title="isEditing ? '编辑药品' : '新建药品'" width="560px">
      <el-form label-width="90px" data-testid="drug-form">
        <el-form-item label="编码">
          <el-input v-model="form.code" data-testid="drug-code" :disabled="isEditing" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" data-testid="drug-name" />
        </el-form-item>
        <el-form-item label="通用名">
          <el-input v-model="form.genericName" data-testid="drug-generic" />
        </el-form-item>
        <el-form-item label="规格">
          <el-input v-model="form.specification" data-testid="drug-spec" />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="form.unit" data-testid="drug-unit" />
        </el-form-item>
        <el-form-item label="剂型">
          <el-input v-model="form.dosageForm" data-testid="drug-form-type" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="form.category" data-testid="drug-category" />
        </el-form-item>
        <el-form-item label="麻精">
          <el-switch v-model="form.narcotic" data-testid="drug-narcotic" />
        </el-form-item>
        <el-form-item label="高危">
          <el-switch v-model="form.highRisk" data-testid="drug-high-risk" />
        </el-form-item>
        <el-form-item label="别名">
          <el-input
            v-model="form.aliasesText"
            data-testid="drug-aliases"
            placeholder="多个别名用中文逗号分隔"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" data-testid="drug-remark" type="textarea" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="drug-submit" @click="submit">
          保存
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.drug-catalog {
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
