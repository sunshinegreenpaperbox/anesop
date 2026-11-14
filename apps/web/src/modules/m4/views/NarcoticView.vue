<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'
import {
  createCabinetHandover,
  createDailyClose,
  createNarcoticRegister,
  expectedClosing,
  getPharmacyAdapterStatus,
  listCabinetHandovers,
  listDrugBaselines,
  listDrugs,
  listNarcoticDepartments,
  listNarcoticRegisters,
  shiftLabels,
  updateDrugBaselines,
  type CabinetHandover,
  type CreateCabinetHandoverRequest,
  type CreateDailyCloseRequest,
  type DailyClose,
  type Drug,
  type DepartmentOption,
  type DrugBaseline,
  type NarcoticRegister,
  type PharmacyAdapterStatus,
} from '../api/m4'

/**
 * 麻精五专（M4-M15-01，契约 drug.yaml 0.1.0）。
 *
 * 专人/专柜/专册/专账/专处方的最小实现：专册登记、专柜交接、基数维护、日结/班结。
 * - 专册/交接/日结 append-only：页面只新增，不提供修改/删除（更正另起记录）；
 * - 双人签字必须不同人（前端先拦一次，服务端 400 为准）；
 * - 日结平账：页面实时提示“期初 + 入库 − 使用 − 退回 = 期末”，不平由服务端 409 并给出
 *   details；系统不替代临床/财务判断（AGENTS H-06）；
 * - 班次两班制（DAY/NIGHT）为演示口径，三班制【待确认】；
 * - 药房适配器 EXT-02 为 SIMULATED，失败时显示降级（本地字典继续可用）。
 */

const auth = useAuthStore()

const adapter = ref<PharmacyAdapterStatus | null>(null)
const drugs = ref<Drug[]>([])
const registers = ref<NarcoticRegister[]>([])
const handovers = ref<CabinetHandover[]>([])
const baselines = ref<DrugBaseline[]>([])
const closes = ref<DailyClose[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)
const activeTab = ref('registers')
const departments = ref<DepartmentOption[]>([])
const departmentId = ref('')

const canManage = computed(() => auth.hasPermission('m4:narcotic:manage'))
const currentUserId = computed(() => auth.user?.userId ?? '')

const registerForm = reactive({
  drugId: '',
  batchNo: '',
  quantity: 1,
  unit: '支',
  prescriptionNo: '',
  usedBy: '',
  usageAt: nowLocal(),
  destination: '术中镇痛（演示）',
  firstSignerId: '',
  firstSignerName: '',
  secondSignerId: '',
  secondSignerName: '',
})

const handoverForm = reactive({
  cabinetCode: 'CAB-ANES-01',
  shift: 'DAY' as 'DAY' | 'NIGHT',
  handedOverAt: nowLocal(),
  handoverFromId: '',
  handoverFromName: '',
  handoverToId: '',
  handoverToName: '',
  items: [{ drugId: '', quantity: 1, unit: '支' }],
  firstSignerId: '',
  firstSignerName: '',
  secondSignerId: '',
  secondSignerName: '',
})

const baselineDialogVisible = ref(false)
const baselineForm = reactive({ drugId: '', cabinetCode: '', quantity: 0, version: 1 })

const closeForm = reactive({
  closeDate: todayLocal(),
  shift: 'DAY' as 'DAY' | 'NIGHT',
  cabinetCode: 'CAB-ANES-01',
  openingQuantity: 0,
  inboundQuantity: 0,
  usedQuantity: 0,
  returnedQuantity: 0,
  closingQuantity: 0,
})

const expected = computed(() => expectedClosing(closeForm))
const balanced = computed(() => Math.abs(expected.value - closeForm.closingQuantity) < 0.005)
const closeErrorDetails = computed(() => error.value?.details ?? [])

function nowLocal(): string {
  return toLocalInput(new Date())
}

function todayLocal(): string {
  const date = new Date()
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function toLocalInput(date: Date): string {
  const pad = (value: number): string => String(value).padStart(2, '0')
  const offsetMinutes = -date.getTimezoneOffset()
  const sign = offsetMinutes >= 0 ? '+' : '-'
  const absolute = Math.abs(offsetMinutes)
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}` +
    `${sign}${pad(Math.floor(absolute / 60))}:${pad(absolute % 60)}`
  )
}

function handleError(cause: unknown): void {
  error.value = cause instanceof ApiError ? cause : null
  ElMessage.error(cause instanceof ApiError ? cause.message : '请求失败，请稍后重试')
}

async function loadAdapter(): Promise<void> {
  try {
    adapter.value = await getPharmacyAdapterStatus()
  } catch (cause) {
    handleError(cause)
  }
}

async function loadDepartments(): Promise<void> {
  try {
    const list = await listNarcoticDepartments()
    departments.value = list.items
  } catch (cause) {
    handleError(cause)
  }
}

async function loadDrugs(): Promise<void> {
  try {
    const list = await listDrugs({ size: 100 })
    drugs.value = list.items
    if (!registerForm.drugId && list.items.length > 0) {
      registerForm.drugId = list.items[0].drugId
    }
    if (handoverForm.items[0] && !handoverForm.items[0].drugId && list.items.length > 0) {
      handoverForm.items[0].drugId = list.items[0].drugId
    }
  } catch (cause) {
    handleError(cause)
  }
}

async function loadAll(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const [registerList, handoverList, baselineList] = await Promise.all([
      listNarcoticRegisters({ limit: 50 }),
      listCabinetHandovers({ limit: 50 }),
      listDrugBaselines({}),
    ])
    registers.value = registerList.items
    handovers.value = handoverList.items
    baselines.value = baselineList.items
  } catch (cause) {
    handleError(cause)
  } finally {
    loading.value = false
  }
}

function useMeAsSigner(): void {
  if (currentUserId.value) {
    registerForm.firstSignerId = currentUserId.value
    handoverForm.firstSignerId = currentUserId.value
    handoverForm.handoverFromId = currentUserId.value
  }
}

function drugName(drugId: string): string {
  return drugs.value.find((drug) => drug.drugId === drugId)?.name ?? drugId
}

async function submitRegister(): Promise<void> {
  if (!registerForm.drugId || !registerForm.batchNo.trim() || !registerForm.prescriptionNo.trim()) {
    ElMessage.warning('药品、批号与处方号不能为空')
    return
  }
  if (!registerForm.firstSignerId || !registerForm.secondSignerId) {
    ElMessage.warning('麻精专册必须双人签字')
    return
  }
  if (registerForm.firstSignerId === registerForm.secondSignerId) {
    ElMessage.warning('第二签字人不得与第一签字人为同一人')
    return
  }
  if (!departmentId.value) {
    ElMessage.warning('请先选择科室（麻精五专按科室留痕）')
    return
  }
  saving.value = true
  error.value = null
  try {
    await createNarcoticRegister({
      drugId: registerForm.drugId,
      batchNo: registerForm.batchNo.trim(),
      quantity: registerForm.quantity,
      unit: registerForm.unit.trim(),
      prescriptionNo: registerForm.prescriptionNo.trim(),
      usedBy: registerForm.usedBy.trim(),
      departmentId: departmentId.value,
      usageAt: registerForm.usageAt,
      destination: registerForm.destination,
      firstSignerId: registerForm.firstSignerId,
      firstSignerName: registerForm.firstSignerName.trim() || '演示签字人（合成）',
      secondSignerId: registerForm.secondSignerId,
      secondSignerName: registerForm.secondSignerName.trim() || '演示核对人（合成）',
    })
    ElMessage.success('专册已登记（append-only，更正请另起记录）')
    await loadAll()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

function addHandoverItem(): void {
  handoverForm.items.push({ drugId: drugs.value[0]?.drugId ?? '', quantity: 1, unit: '支' })
}

function removeHandoverItem(index: number): void {
  if (handoverForm.items.length > 1) {
    handoverForm.items.splice(index, 1)
  }
}

async function submitHandover(): Promise<void> {
  if (!handoverForm.firstSignerId || !handoverForm.secondSignerId) {
    ElMessage.warning('专柜交接必须双人签字')
    return
  }
  if (handoverForm.firstSignerId === handoverForm.secondSignerId) {
    ElMessage.warning('第二签字人不得与第一签字人为同一人')
    return
  }
  if (handoverForm.handoverFromId === handoverForm.handoverToId) {
    ElMessage.warning('接班人不得与交班人为同一人')
    return
  }
  if (!departmentId.value) {
    ElMessage.warning('请先选择科室（麻精五专按科室留痕）')
    return
  }
  saving.value = true
  error.value = null
  try {
    const payload: CreateCabinetHandoverRequest = {
      cabinetCode: handoverForm.cabinetCode.trim(),
      departmentId: departmentId.value,
      shift: handoverForm.shift,
      handedOverAt: handoverForm.handedOverAt,
      handoverFromId: handoverForm.handoverFromId,
      handoverFromName: handoverForm.handoverFromName.trim() || '演示交班人（合成）',
      handoverToId: handoverForm.handoverToId,
      handoverToName: handoverForm.handoverToName.trim() || '演示接班人（合成）',
      items: handoverForm.items.map((item) => ({
        drugId: item.drugId,
        quantity: item.quantity,
        unit: item.unit,
      })),
      firstSignerId: handoverForm.firstSignerId,
      firstSignerName: handoverForm.firstSignerName.trim() || '演示交班人（合成）',
      secondSignerId: handoverForm.secondSignerId,
      secondSignerName: handoverForm.secondSignerName.trim() || '演示接班人（合成）',
    }
    await createCabinetHandover(payload)
    ElMessage.success('专柜交接已登记（append-only）')
    await loadAll()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

function openBaseline(baseline: DrugBaseline): void {
  baselineForm.drugId = baseline.drugId
  baselineForm.cabinetCode = baseline.cabinetCode
  baselineForm.quantity = baseline.quantity
  baselineForm.version = baseline.version
  baselineDialogVisible.value = true
}

async function submitBaseline(): Promise<void> {
  if (!departmentId.value) {
    ElMessage.warning('请先选择科室（麻精五专按科室留痕）')
    return
  }
  saving.value = true
  error.value = null
  try {
    await updateDrugBaselines({
      items: [
        {
          drugId: baselineForm.drugId,
          cabinetCode: baselineForm.cabinetCode,
          departmentId: departmentId.value,
          quantity: baselineForm.quantity,
          version: baselineForm.version,
        },
      ],
    })
    ElMessage.success('基数已更新（版本 +1）')
    baselineDialogVisible.value = false
    await loadAll()
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

async function submitClose(): Promise<void> {
  if (!balanced.value) {
    ElMessage.warning('平账校验未通过：期末必须等于期初 + 入库 − 使用 − 退回')
  }
  if (!departmentId.value) {
    ElMessage.warning('请先选择科室（麻精五专按科室留痕）')
    return
  }
  saving.value = true
  error.value = null
  try {
    const payload: CreateDailyCloseRequest = {
      closeDate: closeForm.closeDate,
      shift: closeForm.shift,
      cabinetCode: closeForm.cabinetCode.trim(),
      departmentId: departmentId.value,
      openingQuantity: closeForm.openingQuantity,
      inboundQuantity: closeForm.inboundQuantity,
      usedQuantity: closeForm.usedQuantity,
      returnedQuantity: closeForm.returnedQuantity,
      closingQuantity: closeForm.closingQuantity,
    }
    const close = await createDailyClose(payload)
    closes.value = [close, ...closes.value]
    ElMessage.success(`日结成功：${shiftLabels[closeForm.shift]} 平账完成（append-only）`)
  } catch (cause) {
    handleError(cause)
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  useMeAsSigner()
  await loadAdapter()
  await loadDepartments()
  await loadDrugs()
  await loadAll()
})
</script>

<template>
  <section class="narcotic-page">
    <header class="page-header">
      <div>
        <h1>麻精五专</h1>
        <p class="hint">
          专人/专柜/专册/专账/专处方：专册登记、专柜交接、基数维护与日结/班结；均
          append-only，更正另起记录。
        </p>
      </div>
      <el-select
        v-model="departmentId"
        data-testid="narcotic-department"
        placeholder="请选择科室（必选）"
        filterable
        style="width: 280px"
      >
        <el-option
          v-for="department in departments"
          :key="department.departmentId"
          :label="`${department.name}（${department.code}）`"
          :value="department.departmentId"
        />
      </el-select>
    </header>

    <el-alert
      v-if="!canManage"
      type="warning"
      title="无麻精五专维护权限（需要 m4:narcotic:manage）"
      :closable="false"
      show-icon
      data-testid="narcotic-forbidden"
    />

    <el-card class="adapter" data-testid="adapter-card">
      <div class="adapter-row">
        <strong>药房适配器（EXT-02，模拟）</strong>
        <el-tag v-if="adapter" :type="adapter.degraded ? 'warning' : 'success'" size="small">
          {{ adapter.degraded ? '已降级（本地字典可用）' : '模拟同步正常' }}
        </el-tag>
        <span v-if="adapter" class="hint" data-testid="adapter-message">{{ adapter.message }}</span>
        <el-button size="small" data-testid="adapter-refresh" @click="loadAdapter">刷新</el-button>
      </div>
    </el-card>

    <el-alert
      v-if="error"
      type="error"
      :title="error.status === 409 ? '冲突：状态/版本/平账校验未通过' : '麻精五专操作失败'"
      :description="`${error.message}${error.traceId ? `（traceId: ${error.traceId}）` : ''}`"
      show-icon
      :closable="false"
      data-testid="narcotic-error"
    />
    <ul v-if="closeErrorDetails.length > 0" data-testid="close-details">
      <li v-for="detail in closeErrorDetails" :key="detail.field">
        {{ detail.field }}：{{ detail.reason }}
      </li>
    </ul>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="专册登记" name="registers">
        <el-form label-width="110px" class="form-grid" data-testid="register-form">
          <el-form-item label="药品">
            <el-select v-model="registerForm.drugId" data-testid="register-drug">
              <el-option
                v-for="drug in drugs"
                :key="drug.drugId"
                :label="drug.name"
                :value="drug.drugId"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="批号">
            <el-input v-model="registerForm.batchNo" data-testid="register-batch" />
          </el-form-item>
          <el-form-item label="数量">
            <el-input-number
              v-model="registerForm.quantity"
              :min="0.01"
              :step="1"
              data-testid="register-quantity"
            />
          </el-form-item>
          <el-form-item label="单位">
            <el-input v-model="registerForm.unit" data-testid="register-unit" />
          </el-form-item>
          <el-form-item label="处方号">
            <el-input v-model="registerForm.prescriptionNo" data-testid="register-rx" />
          </el-form-item>
          <el-form-item label="使用人">
            <el-input v-model="registerForm.usedBy" data-testid="register-used-by" />
          </el-form-item>
          <el-form-item label="使用时间">
            <el-input v-model="registerForm.usageAt" data-testid="register-usage-at" />
          </el-form-item>
          <el-form-item label="去向">
            <el-input v-model="registerForm.destination" data-testid="register-destination" />
          </el-form-item>
          <el-form-item label="第一签字人">
            <el-input v-model="registerForm.firstSignerId" data-testid="register-signer-a" />
          </el-form-item>
          <el-form-item label="第二签字人">
            <el-input v-model="registerForm.secondSignerId" data-testid="register-signer-b" />
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              :loading="saving"
              data-testid="register-submit"
              @click="submitRegister"
            >
              登记专册
            </el-button>
          </el-form-item>
        </el-form>

        <el-table :data="registers" data-testid="register-table" border>
          <el-table-column label="药品" min-width="140">
            <template #default="{ row }">{{ row.drugName }}</template>
          </el-table-column>
          <el-table-column prop="batchNo" label="批号" width="150" />
          <el-table-column prop="quantity" label="数量" width="90" />
          <el-table-column prop="prescriptionNo" label="处方号" width="150" />
          <el-table-column prop="usedBy" label="使用人" width="150" />
          <el-table-column prop="usageAt" label="使用时间" width="200" />
          <el-table-column label="双人签字" width="120">
            <template #default="{ row }">
              {{ row.firstSignerName }} / {{ row.secondSignerName }}
            </template>
          </el-table-column>
          <template #empty><span data-testid="register-empty">暂无专册记录</span></template>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="专柜交接" name="handovers">
        <el-form label-width="110px" class="form-grid" data-testid="handover-form">
          <el-form-item label="柜号">
            <el-input v-model="handoverForm.cabinetCode" data-testid="handover-cabinet" />
          </el-form-item>
          <el-form-item label="班次">
            <el-select v-model="handoverForm.shift" data-testid="handover-shift">
              <el-option label="白班" value="DAY" />
              <el-option label="夜班" value="NIGHT" />
            </el-select>
          </el-form-item>
          <el-form-item label="交接时间">
            <el-input v-model="handoverForm.handedOverAt" data-testid="handover-time" />
          </el-form-item>
          <el-form-item label="交班人">
            <el-input v-model="handoverForm.handoverFromId" data-testid="handover-from" />
          </el-form-item>
          <el-form-item label="接班人">
            <el-input v-model="handoverForm.handoverToId" data-testid="handover-to" />
          </el-form-item>
          <el-form-item label="第一签字人">
            <el-input v-model="handoverForm.firstSignerId" data-testid="handover-signer-a" />
          </el-form-item>
          <el-form-item label="第二签字人">
            <el-input v-model="handoverForm.secondSignerId" data-testid="handover-signer-b" />
          </el-form-item>
        </el-form>
        <div class="items" data-testid="handover-items">
          <div v-for="(item, index) in handoverForm.items" :key="index" class="item-row">
            <el-select v-model="item.drugId" style="width: 220px">
              <el-option
                v-for="drug in drugs"
                :key="drug.drugId"
                :label="drug.name"
                :value="drug.drugId"
              />
            </el-select>
            <el-input-number v-model="item.quantity" :min="0" :step="1" />
            <el-input v-model="item.unit" style="width: 90px" />
            <el-button link type="danger" @click="removeHandoverItem(index)">移除</el-button>
          </div>
          <el-button size="small" data-testid="handover-add-item" @click="addHandoverItem">
            添加品种
          </el-button>
          <el-button
            type="primary"
            :loading="saving"
            data-testid="handover-submit"
            @click="submitHandover"
          >
            登记交接
          </el-button>
        </div>

        <el-table :data="handovers" data-testid="handover-table" border>
          <el-table-column prop="cabinetCode" label="柜号" width="140" />
          <el-table-column label="班次" width="90">
            <template #default="{ row }">{{ shiftLabels[row.shift] ?? row.shift }}</template>
          </el-table-column>
          <el-table-column prop="handedOverAt" label="交接时间" width="200" />
          <el-table-column label="交班 → 接班" min-width="200">
            <template #default="{ row }">
              {{ row.handoverFromName }} → {{ row.handoverToName }}
            </template>
          </el-table-column>
          <el-table-column label="品种数" width="90">
            <template #default="{ row }">{{ row.items.length }}</template>
          </el-table-column>
          <template #empty><span data-testid="handover-empty">暂无交接记录</span></template>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="基数" name="baselines">
        <el-table :data="baselines" data-testid="baseline-table" border>
          <el-table-column prop="cabinetCode" label="柜号" width="150" />
          <el-table-column prop="drugName" label="药品" min-width="160" />
          <el-table-column prop="quantity" label="基数" width="100" />
          <el-table-column prop="unit" label="单位" width="80" />
          <el-table-column prop="version" label="版本" width="80" />
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button link type="primary" data-testid="baseline-edit" @click="openBaseline(row)">
                调整
              </el-button>
            </template>
          </el-table-column>
          <template #empty><span data-testid="baseline-empty">暂无基数记录</span></template>
        </el-table>
        <el-dialog v-model="baselineDialogVisible" title="调整基数" width="420px">
          <el-form label-width="90px">
            <el-form-item label="药品">{{ drugName(baselineForm.drugId) }}</el-form-item>
            <el-form-item label="柜号">{{ baselineForm.cabinetCode }}</el-form-item>
            <el-form-item label="基数">
              <el-input-number
                v-model="baselineForm.quantity"
                :min="0"
                :step="1"
                data-testid="baseline-quantity"
              />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="baselineDialogVisible = false">取消</el-button>
            <el-button
              type="primary"
              :loading="saving"
              data-testid="baseline-submit"
              @click="submitBaseline"
            >
              保存
            </el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <el-tab-pane label="日结/班结" name="closes">
        <el-form label-width="110px" class="form-grid" data-testid="close-form">
          <el-form-item label="业务日">
            <el-input v-model="closeForm.closeDate" data-testid="close-date" />
          </el-form-item>
          <el-form-item label="班次">
            <el-select v-model="closeForm.shift" data-testid="close-shift">
              <el-option label="白班" value="DAY" />
              <el-option label="夜班" value="NIGHT" />
            </el-select>
          </el-form-item>
          <el-form-item label="柜号">
            <el-input v-model="closeForm.cabinetCode" data-testid="close-cabinet" />
          </el-form-item>
          <el-form-item label="期初">
            <el-input-number
              v-model="closeForm.openingQuantity"
              :min="0"
              data-testid="close-opening"
            />
          </el-form-item>
          <el-form-item label="入库">
            <el-input-number
              v-model="closeForm.inboundQuantity"
              :min="0"
              data-testid="close-inbound"
            />
          </el-form-item>
          <el-form-item label="使用">
            <el-input-number v-model="closeForm.usedQuantity" :min="0" data-testid="close-used" />
          </el-form-item>
          <el-form-item label="退回">
            <el-input-number
              v-model="closeForm.returnedQuantity"
              :min="0"
              data-testid="close-returned"
            />
          </el-form-item>
          <el-form-item label="期末（实盘）">
            <el-input-number
              v-model="closeForm.closingQuantity"
              :min="0"
              data-testid="close-closing"
            />
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              :loading="saving"
              data-testid="close-submit"
              @click="submitClose"
            >
              提交日结
            </el-button>
          </el-form-item>
        </el-form>
        <p class="hint" data-testid="close-expected">
          平账校验：期初 + 入库 − 使用 − 退回 = 期末（期望 {{ expected }}，实盘
          {{ closeForm.closingQuantity }}）{{ balanced ? '，一致' : '，不一致' }}
        </p>

        <el-table :data="closes" data-testid="close-table" border>
          <el-table-column prop="closeDate" label="业务日" width="130" />
          <el-table-column label="班次" width="90">
            <template #default="{ row }">{{ shiftLabels[row.shift] ?? row.shift }}</template>
          </el-table-column>
          <el-table-column prop="cabinetCode" label="柜号" width="140" />
          <el-table-column prop="openingQuantity" label="期初" width="80" />
          <el-table-column prop="inboundQuantity" label="入库" width="80" />
          <el-table-column prop="usedQuantity" label="使用" width="80" />
          <el-table-column prop="returnedQuantity" label="退回" width="80" />
          <el-table-column prop="closingQuantity" label="期末" width="80" />
          <template #empty><span data-testid="close-empty">暂无日结记录</span></template>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </section>
</template>

<style scoped>
.narcotic-page {
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
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 4px 0 0;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}
.adapter-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.items {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 12px;
}
.item-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
</style>
