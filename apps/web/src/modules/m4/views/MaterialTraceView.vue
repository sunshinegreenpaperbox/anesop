<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'
import {
  getUdiTrace,
  listMaterialDepartments,
  listMaterials,
  movementTypeLabels,
  registerUdiInstance,
  udiStatusLabels,
  useUdiInstance,
  type DepartmentOption,
  type Material,
  type RegisterUdiInstanceRequest,
  type UdiInstance,
  type UdiTrace,
} from '../api/material'

/**
 * UDI 使用与追溯（M4-M14-01，契约 material.yaml 0.1.0）。
 *
 * - 登记 = 逐件入库（UDI 实例 + 批次库存 +1 + IN 流水），UDI-DI + UDI-PI 一物一码；
 * - 使用 = 绑定麻醉记录/台次，状态 IN_STOCK → USED，重复使用 409；
 * - 追溯链 material → UDI → record 只回传标识，不含患者信息；
 * - 扫码式输入为占位：本期手动输入/粘贴 UDI 串，真实扫码枪/UDI 解析规则【待确认】。
 */

const auth = useAuthStore()

const departments = ref<DepartmentOption[]>([])
const materials = ref<Material[]>([])
const udiInput = ref('')
const trace = ref<UdiTrace | null>(null)
const traceError = ref<ApiError | null>(null)
const loading = ref(false)
const registering = ref(false)
const using = ref(false)
const registerResult = ref('')
const useResult = ref('')

const registerForm = reactive({
  materialId: '',
  udiDi: '',
  udiPi: '',
  serialNumber: '',
  batchNo: '',
  expiryDate: '',
  departmentId: '',
  counterparty: '',
})

const useForm = reactive({ recordId: '', scheduleId: '' })

const canManageUdi = computed(() => auth.hasPermission('m4:udi:manage'))
const traceErrorTitle = computed(() => {
  if (!traceError.value) return ''
  if (traceError.value.status === 404) return 'UDI 实例不存在或不在当前科室数据范围'
  if (traceError.value.status === 403)
    return '无权访问该 UDI（需要 m4:material:read 与科室数据范围）'
  return 'UDI 追溯查询失败'
})
const statusTagType = computed(() => {
  if (!trace.value) return 'info'
  if (trace.value.instance.status === 'USED') return 'success'
  if (trace.value.instance.status === 'RETURNED') return 'warning'
  return 'info'
})

function handleError(cause: unknown): void {
  traceError.value = cause instanceof ApiError ? cause : null
  ElMessage.error(cause instanceof ApiError ? cause.message : '请求失败，请稍后重试')
}

async function loadReference(): Promise<void> {
  try {
    const [departmentList, materialList] = await Promise.all([
      listMaterialDepartments(),
      listMaterials({ status: 'ACTIVE', size: 200 }),
    ])
    departments.value = departmentList.items
    materials.value = materialList.items
    if (!registerForm.departmentId && departments.value.length > 0) {
      registerForm.departmentId = departments.value[0].departmentId
    }
    if (!registerForm.materialId && materials.value.length > 0) {
      registerForm.materialId = materials.value[0].materialId
    }
  } catch (cause) {
    handleError(cause)
  }
}

async function queryTrace(): Promise<void> {
  const udi = udiInput.value.trim()
  if (!udi) {
    trace.value = null
    ElMessage.warning('请输入或扫描 UDI（UDI-DI^UDI-PI）')
    return
  }
  loading.value = true
  traceError.value = null
  try {
    trace.value = await getUdiTrace(udi)
  } catch (cause) {
    trace.value = null
    handleError(cause)
  } finally {
    loading.value = false
  }
}

function applyMaterial(materialId: string): void {
  registerForm.materialId = materialId
  const material = materials.value.find((item) => item.materialId === materialId)
  if (material?.udiDi) {
    registerForm.udiDi = material.udiDi
  }
}

async function register(): Promise<void> {
  if (
    !registerForm.materialId ||
    !registerForm.udiDi.trim() ||
    !registerForm.udiPi.trim() ||
    !registerForm.batchNo.trim() ||
    !registerForm.counterparty.trim()
  ) {
    ElMessage.warning('耗材、UDI-DI、UDI-PI、批次与来源不能为空')
    return
  }
  registering.value = true
  try {
    const payload: RegisterUdiInstanceRequest = {
      materialId: registerForm.materialId,
      udiDi: registerForm.udiDi.trim(),
      udiPi: registerForm.udiPi.trim(),
      serialNumber: registerForm.serialNumber.trim() || undefined,
      batchNo: registerForm.batchNo.trim(),
      expiryDate: registerForm.expiryDate || undefined,
      departmentId: registerForm.departmentId,
      counterparty: registerForm.counterparty.trim(),
    }
    const instance: UdiInstance = await registerUdiInstance(payload)
    registerResult.value = `已登记 ${instance.udi}（${udiStatusLabels[instance.status] ?? instance.status}）`
    ElMessage.success(registerResult.value)
    udiInput.value = instance.udi
    await queryTrace()
  } catch (cause) {
    handleError(cause)
  } finally {
    registering.value = false
  }
}

async function consume(): Promise<void> {
  const udi = udiInput.value.trim()
  if (!udi || !useForm.recordId.trim()) {
    ElMessage.warning('请输入 UDI 与麻醉记录标识')
    return
  }
  using.value = true
  try {
    const instance = await useUdiInstance(udi, {
      recordId: useForm.recordId.trim(),
      scheduleId: useForm.scheduleId.trim() || undefined,
    })
    useResult.value = `已使用并绑定记录 ${instance.recordId}（${udiStatusLabels[instance.status] ?? instance.status}）`
    ElMessage.success(useResult.value)
    await queryTrace()
  } catch (cause) {
    handleError(cause)
  } finally {
    using.value = false
  }
}

onMounted(loadReference)
</script>

<template>
  <section class="material-trace">
    <header class="page-header">
      <div>
        <h1>UDI 使用与追溯</h1>
        <p class="hint">
          扫码式输入占位：手动输入/粘贴 UDI（UDI-DI^UDI-PI）；一物一码，重复登记或重复使用会被拒绝。
        </p>
      </div>
    </header>

    <el-alert
      v-if="traceError"
      type="error"
      :title="traceErrorTitle"
      :description="`${traceError.message}${traceError.traceId ? `（traceId: ${traceError.traceId}）` : ''}`"
      show-icon
      :closable="false"
      data-testid="udi-error"
    />

    <section class="card">
      <h2>扫码/输入 UDI</h2>
      <div class="row">
        <el-input
          v-model="udiInput"
          data-testid="udi-input"
          placeholder="如 06937000000001^SN-DEMO-001"
          clearable
          @keyup.enter="queryTrace"
        />
        <el-button type="primary" :loading="loading" data-testid="udi-query" @click="queryTrace">
          查询追溯链
        </el-button>
      </div>
      <p v-if="!trace" class="hint" data-testid="udi-empty">尚未查询 UDI 追溯链</p>
      <template v-else>
        <div class="status-line">
          <el-tag :type="statusTagType" size="small" data-testid="udi-status">
            {{ udiStatusLabels[trace.instance.status] ?? trace.instance.status }}
          </el-tag>
          <span class="hint">{{ trace.instance.udi }}</span>
        </div>
        <p class="hint" data-testid="udi-material">
          耗材：{{ trace.material.name }}（{{ trace.material.code }}）；批次
          {{ trace.instance.batchNo }}；效期 {{ trace.instance.expiryDate ?? '无' }}
        </p>
        <p v-if="trace.record" class="hint" data-testid="udi-record">
          使用绑定记录 {{ trace.record.recordId }} / 台次 {{ trace.record.scheduleId }}
        </p>
        <h3>出入库流水</h3>
        <el-table :data="trace.movements" data-testid="udi-movements" border style="width: 100%">
          <el-table-column label="类型" width="90">
            <template #default="{ row }">
              {{ movementTypeLabels[row.movementType] ?? row.movementType }}
            </template>
          </el-table-column>
          <el-table-column prop="quantity" label="数量" width="80" />
          <el-table-column prop="balanceAfter" label="余额" width="80" />
          <el-table-column prop="counterparty" label="来源/去向" min-width="140" />
          <el-table-column prop="operatorName" label="操作人" width="120" />
          <el-table-column prop="occurredAt" label="时间" width="190" />
          <template #empty>
            <span>暂无流水</span>
          </template>
        </el-table>
      </template>
    </section>

    <div class="grid">
      <section v-if="canManageUdi" class="card">
        <h2>UDI 登记（逐件入库）</h2>
        <el-form label-width="90px" data-testid="udi-register-form">
          <el-form-item label="耗材">
            <el-select
              v-model="registerForm.materialId"
              data-testid="udi-register-material"
              filterable
              style="width: 100%"
              @change="applyMaterial"
            >
              <el-option
                v-for="material in materials"
                :key="material.materialId"
                :label="`${material.name}（${material.code}）`"
                :value="material.materialId"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="UDI-DI">
            <el-input v-model="registerForm.udiDi" data-testid="udi-register-di" />
          </el-form-item>
          <el-form-item label="UDI-PI">
            <el-input
              v-model="registerForm.udiPi"
              data-testid="udi-register-pi"
              placeholder="序列号/批号组合（解析规则待确认）"
            />
          </el-form-item>
          <el-form-item label="序列号">
            <el-input v-model="registerForm.serialNumber" data-testid="udi-register-serial" />
          </el-form-item>
          <el-form-item label="批次">
            <el-input v-model="registerForm.batchNo" data-testid="udi-register-batch" />
          </el-form-item>
          <el-form-item label="效期">
            <el-date-picker
              v-model="registerForm.expiryDate"
              data-testid="udi-register-expiry"
              type="date"
              value-format="YYYY-MM-DD"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="科室">
            <el-select
              v-model="registerForm.departmentId"
              data-testid="udi-register-department"
              style="width: 100%"
            >
              <el-option
                v-for="department in departments"
                :key="department.departmentId"
                :label="department.name"
                :value="department.departmentId"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="来源">
            <el-input v-model="registerForm.counterparty" data-testid="udi-register-source" />
          </el-form-item>
        </el-form>
        <p v-if="registerResult" class="hint" data-testid="udi-register-result">
          {{ registerResult }}
        </p>
        <el-button
          type="primary"
          :loading="registering"
          data-testid="udi-register-submit"
          @click="register"
        >
          登记入库
        </el-button>
      </section>

      <section v-if="canManageUdi" class="card">
        <h2>UDI 使用（绑定记录/台次）</h2>
        <el-form label-width="110px" data-testid="udi-use-form">
          <el-form-item label="麻醉记录">
            <el-input v-model="useForm.recordId" data-testid="udi-use-record" />
          </el-form-item>
          <el-form-item label="台次">
            <el-input
              v-model="useForm.scheduleId"
              data-testid="udi-use-schedule"
              placeholder="可选；与记录台次一致"
            />
          </el-form-item>
        </el-form>
        <p v-if="useResult" class="hint" data-testid="udi-use-result">{{ useResult }}</p>
        <el-button type="primary" :loading="using" data-testid="udi-use-submit" @click="consume">
          使用并绑定
        </el-button>
      </section>
    </div>
  </section>
</template>

<style scoped>
.material-trace {
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
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 12px;
}
.card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px;
}
.row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.status-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}
h2 {
  font-size: 15px;
  margin: 4px 0 8px;
}
h3 {
  font-size: 13px;
  margin: 12px 0 6px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 4px 0 0;
}
</style>
