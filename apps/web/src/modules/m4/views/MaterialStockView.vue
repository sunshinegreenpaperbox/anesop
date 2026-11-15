<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'
import {
  alertTypeLabels,
  createStockMovement,
  getSpdAdapterStatus,
  listMaterialDepartments,
  listMaterialStock,
  listMaterials,
  listStockAlerts,
  listStockMovements,
  movementTypeLabels,
  syncSpdAdapter,
  type CreateStockMovementRequest,
  type DepartmentOption,
  type Material,
  type MaterialStockAlert,
  type MaterialStockLevel,
  type MovementType,
  type SpdAdapterStatus,
  type StockAlertType,
  type StockMovement,
} from '../api/material'

/**
 * 耗材出入库与 SPD 状态卡（M4-M14-01，契约 material.yaml 0.1.0）。
 *
 * - 批次库存/流水按科室数据范围隔离（SELF/ROOM 403），页面必须显式选择科室；
 * - 数量不足 409（服务端 details 给可用量），效期/高值裸数量规则 422；
 * - 高值耗材入库走 UDI 追溯页逐件登记，本页普通出入库不处理高值；
 * - SPD 适配器（EXT-03）为模拟，同步失败降级但本地库存继续可用。
 * - 库存预警（material.yaml 0.2.0）：低库存（严格低于阈值）/零库存/临期 90 天，
 *   服务端按科室数据范围过滤并提供建议补货量；本页展示预警标签并支持按类型筛选。
 */

const auth = useAuthStore()

const departments = ref<DepartmentOption[]>([])
const materials = ref<Material[]>([])
const departmentId = ref('')
const stockRows = ref<MaterialStockLevel[]>([])
const movements = ref<StockMovement[]>([])
const alerts = ref<MaterialStockAlert[]>([])
const alertType = ref<'' | StockAlertType>('')
const spdStatus = ref<SpdAdapterStatus | null>(null)
const loading = ref(false)
const submitting = ref(false)
const syncing = ref(false)
const error = ref<ApiError | null>(null)
const lastResult = ref('')

const form = reactive({
  movementType: 'IN' as MovementType,
  materialId: '',
  batchNo: '',
  expiryDate: '',
  quantity: 1,
  counterparty: '',
  reason: '',
  udi: '',
})

const canWrite = computed(() => auth.hasPermission('m4:material:write'))
const isHighValue = computed(
  () => materials.value.find((item) => item.materialId === form.materialId)?.highValue ?? false,
)
const errorTitle = computed(() => {
  if (!error.value) return ''
  if (error.value.status === 403)
    return '无权访问该科室库存（需要 m4:material:read 与科室数据范围）'
  if (error.value.status === 409) return '数量不足/批次效期冲突/版本过期，请核对后重试'
  if (error.value.status === 422) return '业务规则拒绝（效期/高值/批次校验）'
  if (error.value.status === 400) return '出入库字段校验失败'
  return '耗材出入库操作失败'
})

function handleError(cause: unknown): void {
  error.value = cause instanceof ApiError ? cause : null
  ElMessage.error(cause instanceof ApiError ? cause.message : '请求失败，请稍后重试')
}

function detailText(cause: ApiError): string {
  if (!cause.details.length) return cause.message
  return cause.details.map((detail) => `${detail.field}: ${detail.reason}`).join('；')
}

async function loadReference(): Promise<void> {
  try {
    const [departmentList, materialList] = await Promise.all([
      listMaterialDepartments(),
      listMaterials({ status: 'ACTIVE', size: 200 }),
    ])
    departments.value = departmentList.items
    materials.value = materialList.items
    if (!departmentId.value && departments.value.length > 0) {
      departmentId.value = departments.value[0].departmentId
    }
    if (!form.materialId && materials.value.length > 0) {
      form.materialId = materials.value[0].materialId
    }
  } catch (cause) {
    handleError(cause)
  }
}

async function loadStock(): Promise<void> {
  if (!departmentId.value) return
  loading.value = true
  error.value = null
  try {
    const [stock, movementList, alertList] = await Promise.all([
      listMaterialStock({ departmentId: departmentId.value, limit: 100 }),
      listStockMovements({ departmentId: departmentId.value, limit: 100 }),
      listStockAlerts({ departmentId: departmentId.value, limit: 200 }),
    ])
    stockRows.value = stock.items
    movements.value = movementList.items
    alerts.value = alertList.items
  } catch (cause) {
    handleError(cause)
  } finally {
    loading.value = false
  }
}

/** 按类型筛选预警（服务端过滤，避免前端截断造成漏报）。 */
async function loadAlerts(): Promise<void> {
  if (!departmentId.value) return
  try {
    const alertList = await listStockAlerts({
      departmentId: departmentId.value,
      alertType: alertType.value || undefined,
      limit: 200,
    })
    alerts.value = alertList.items
  } catch (cause) {
    handleError(cause)
  }
}

/** 预警类型中文标签（未知类型回退原值，避免新增类型时显示空白）。 */
function alertLabel(value: string): string {
  return alertTypeLabels[value as StockAlertType] ?? value
}

/** 预警标签颜色：零库存最严重、低库存次之、临期提示。 */
function alertTagType(value: StockAlertType): 'danger' | 'warning' | 'info' {
  if (value === 'ZERO_STOCK') return 'danger'
  if (value === 'LOW_STOCK') return 'warning'
  return 'info'
}

async function loadSpd(): Promise<void> {
  try {
    spdStatus.value = await getSpdAdapterStatus()
  } catch (cause) {
    handleError(cause)
  }
}

function resetMovement(): void {
  form.movementType = 'IN'
  form.batchNo = ''
  form.expiryDate = ''
  form.quantity = 1
  form.counterparty = ''
  form.reason = ''
  form.udi = ''
}

async function submitMovement(): Promise<void> {
  if (!form.materialId || !form.batchNo.trim() || !form.counterparty.trim()) {
    ElMessage.warning('耗材、批次与来源/去向不能为空')
    return
  }
  if (form.quantity === 0 || (!['ADJUST'].includes(form.movementType) && form.quantity <= 0)) {
    ElMessage.warning('入库/出库/退回数量必须大于 0；盘点调整为带符号且不得为 0')
    return
  }
  if (form.movementType === 'ADJUST' && !form.reason.trim()) {
    ElMessage.warning('盘点调整必须填写原因')
    return
  }
  submitting.value = true
  error.value = null
  try {
    const payload: CreateStockMovementRequest = {
      movementType: form.movementType,
      materialId: form.materialId,
      batchNo: form.batchNo.trim(),
      expiryDate: form.expiryDate || undefined,
      quantity: Number(form.quantity),
      departmentId: departmentId.value,
      counterparty: form.counterparty.trim(),
      reason: form.reason.trim() || undefined,
      udi: form.udi.trim() || undefined,
    }
    const movement = await createStockMovement(payload)
    lastResult.value = `${movementTypeLabels[movement.movementType]}成功，批次余额 ${movement.balanceAfter}`
    ElMessage.success(lastResult.value)
    resetMovement()
    await loadStock()
  } catch (cause) {
    if (cause instanceof ApiError) {
      lastResult.value = detailText(cause)
    }
    handleError(cause)
  } finally {
    submitting.value = false
  }
}

async function syncSpd(): Promise<void> {
  syncing.value = true
  error.value = null
  try {
    const result = await syncSpdAdapter()
    lastResult.value = result.success
      ? `SPD 模拟同步成功（${result.pulledCount}/${result.pushedCount}）`
      : 'SPD 模拟同步失败，已降级为本地库存可用'
    ElMessage[result.success ? 'success' : 'warning'](lastResult.value)
    await loadSpd()
  } catch (cause) {
    handleError(cause)
  } finally {
    syncing.value = false
  }
}

async function init(): Promise<void> {
  await loadReference()
  await Promise.all([loadStock(), loadSpd()])
}

onMounted(init)
</script>

<template>
  <section class="material-stock">
    <header class="page-header">
      <div>
        <h1>耗材出入库</h1>
        <p class="hint">
          批次库存按 材料 + 批次 + 科室 记账；流水只增不改，更正走反向盘点调整并填写原因。
        </p>
      </div>
      <div class="actions">
        <el-select
          v-model="departmentId"
          data-testid="stock-department"
          style="width: 200px"
          @change="loadStock"
        >
          <el-option
            v-for="department in departments"
            :key="department.departmentId"
            :label="department.name"
            :value="department.departmentId"
          />
        </el-select>
        <el-button data-testid="stock-refresh" @click="loadStock">刷新库存</el-button>
      </div>
    </header>

    <el-alert
      v-if="error"
      type="error"
      :title="errorTitle"
      :description="`${error.message}${error.traceId ? `（traceId: ${error.traceId}）` : ''}`"
      show-icon
      :closable="false"
      data-testid="stock-error"
    />
    <p v-if="lastResult" class="hint" data-testid="stock-result">{{ lastResult }}</p>

    <section class="card" data-testid="alert-card">
      <div class="alert-header">
        <h2>库存预警</h2>
        <el-select
          v-model="alertType"
          data-testid="alert-filter"
          size="small"
          style="width: 170px"
          @change="loadAlerts"
        >
          <el-option label="全部预警" value="" />
          <el-option
            v-for="(label, value) in alertTypeLabels"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
      </div>
      <p class="hint">
        低库存 = 可用量严格低于阈值（阈值可空 = 不按阈值预警）；零库存 = 科室可用量合计 0； 临期 =
        存在数量 &gt; 0 且效期 ≤ 90 天的批次。建议补货量 = max(0, 阈值 − 可用量)，
        阈值缺失时不给出补货量（阈值来源【待确认】）。
      </p>
      <el-table :data="alerts" data-testid="alert-table" size="small" border style="width: 100%">
        <el-table-column prop="materialCode" label="编码" width="150" />
        <el-table-column prop="materialName" label="名称" min-width="170" />
        <el-table-column prop="departmentName" label="科室" min-width="140" />
        <el-table-column prop="availableQuantity" label="可用量" width="90" />
        <el-table-column label="阈值" width="90">
          <template #default="{ row }">
            {{ row.lowStockThreshold === null ? '未配置' : row.lowStockThreshold }}
          </template>
        </el-table-column>
        <el-table-column label="建议补货" width="100">
          <template #default="{ row }">
            {{ row.suggestedQuantity === null ? '—' : row.suggestedQuantity }}
          </template>
        </el-table-column>
        <el-table-column label="预警类型" min-width="190">
          <template #default="{ row }">
            <el-tag
              v-for="item in row.alertTypes"
              :key="item"
              :type="alertTagType(item)"
              size="small"
              class="alert-tag"
            >
              {{ alertLabel(item) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近效期" width="150">
          <template #default="{ row }">
            <span v-if="row.nearestExpiryDate">
              {{ row.nearestExpiryDate }}（{{ row.daysToExpiry }} 天）
            </span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <template #empty>
          <span data-testid="alert-empty">暂无库存预警</span>
        </template>
      </el-table>
    </section>

    <div class="grid">
      <section class="card" data-testid="spd-card">
        <h2>SPD 适配器（EXT-03，模拟）</h2>
        <p v-if="!spdStatus" class="hint">尚未获取状态</p>
        <template v-else>
          <div class="status-line" data-testid="spd-status">
            <el-tag :type="spdStatus.degraded ? 'warning' : 'success'" size="small">
              {{ spdStatus.degraded ? '降级（本地库存可用）' : '模拟同步正常' }}
            </el-tag>
            <span class="hint">待同步 {{ spdStatus.pendingSyncCount }} 条</span>
          </div>
          <p class="hint">{{ spdStatus.message }}</p>
          <p class="hint">
            最近同步：{{ spdStatus.lastSyncedAt ? spdStatus.lastSyncedAt : '从未同步' }}
          </p>
          <el-button
            v-if="canWrite"
            size="small"
            :loading="syncing"
            data-testid="spd-sync"
            @click="syncSpd"
          >
            触发模拟同步
          </el-button>
        </template>
      </section>

      <section class="card">
        <h2>登记出入库</h2>
        <el-form label-width="90px" data-testid="movement-form">
          <el-form-item label="类型">
            <el-select v-model="form.movementType" data-testid="movement-type" style="width: 100%">
              <el-option
                v-for="(label, value) in movementTypeLabels"
                :key="value"
                :label="label"
                :value="value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="耗材">
            <el-select
              v-model="form.materialId"
              data-testid="movement-material"
              filterable
              style="width: 100%"
            >
              <el-option
                v-for="material in materials"
                :key="material.materialId"
                :label="`${material.name}（${material.code}）`"
                :value="material.materialId"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="批次">
            <el-input v-model="form.batchNo" data-testid="movement-batch" />
          </el-form-item>
          <el-form-item label="效期">
            <el-date-picker
              v-model="form.expiryDate"
              data-testid="movement-expiry"
              type="date"
              value-format="YYYY-MM-DD"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="数量">
            <el-input-number
              v-model="form.quantity"
              data-testid="movement-quantity"
              :step="1"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item label="来源/去向">
            <el-input v-model="form.counterparty" data-testid="movement-counterparty" />
          </el-form-item>
          <el-form-item label="原因">
            <el-input
              v-model="form.reason"
              data-testid="movement-reason"
              placeholder="盘点调整必填"
            />
          </el-form-item>
          <el-form-item v-if="form.movementType === 'RETURN'" label="UDI">
            <el-input
              v-model="form.udi"
              data-testid="movement-udi"
              placeholder="高值耗材退回必须扫描 UDI"
            />
          </el-form-item>
        </el-form>
        <p v-if="isHighValue" class="hint" data-testid="high-value-hint">
          高值耗材：入库请在 UDI 追溯页逐件登记，使用请绑定记录/台次后出库。
        </p>
        <el-button
          v-if="canWrite"
          type="primary"
          :loading="submitting"
          data-testid="movement-submit"
          @click="submitMovement"
        >
          提交
        </el-button>
      </section>
    </div>

    <section>
      <h2>批次库存</h2>
      <el-table
        v-loading="loading"
        :data="stockRows"
        data-testid="stock-table"
        border
        style="width: 100%"
      >
        <el-table-column prop="materialCode" label="编码" width="150" />
        <el-table-column prop="materialName" label="名称" min-width="170" />
        <el-table-column prop="batchNo" label="批次" width="150" />
        <el-table-column prop="quantity" label="数量" width="90" />
        <el-table-column prop="unit" label="单位" width="70" />
        <el-table-column prop="expiryDate" label="效期" width="120" />
        <el-table-column prop="departmentId" label="科室" min-width="160" />
        <template #empty>
          <span data-testid="stock-empty">暂无库存</span>
        </template>
      </el-table>
    </section>

    <section>
      <h2>出入库流水（最近 100 条）</h2>
      <el-table :data="movements" data-testid="movement-table" border style="width: 100%">
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            {{ movementTypeLabels[row.movementType] ?? row.movementType }}
          </template>
        </el-table-column>
        <el-table-column prop="materialName" label="耗材" min-width="150" />
        <el-table-column prop="batchNo" label="批次" width="140" />
        <el-table-column prop="quantity" label="数量" width="80" />
        <el-table-column prop="balanceAfter" label="余额" width="80" />
        <el-table-column prop="counterparty" label="来源/去向" min-width="140" />
        <el-table-column prop="operatorName" label="操作人" width="120" />
        <el-table-column prop="occurredAt" label="时间" width="190" />
        <template #empty>
          <span data-testid="movement-empty">暂无流水</span>
        </template>
      </el-table>
    </section>
  </section>
</template>

<style scoped>
.material-stock {
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
.grid {
  display: grid;
  grid-template-columns: minmax(280px, 1fr) minmax(320px, 1.2fr);
  gap: 12px;
}
.card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px;
}
.status-line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.alert-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.alert-tag {
  margin-right: 4px;
}
h2 {
  font-size: 15px;
  margin: 4px 0 8px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 4px 0 0;
}
</style>
