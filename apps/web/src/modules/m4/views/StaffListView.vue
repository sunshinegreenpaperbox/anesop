<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../../api/http'
import { useAuthStore } from '../../../stores/auth'
import {
  createStaff,
  employmentStatusLabels,
  listStaff,
  listStaffDepartments,
  qualificationTypeLabels,
  updateStaff,
  type CreateStaffRequest,
  type DepartmentOption,
  type Staff,
  type StaffQualificationInput,
  type UpdateStaffRequest,
} from '../api/staff'

/**
 * 人员档案（M4-M13-01，契约 staff.yaml 0.1.0）。
 *
 * - 查询需要 m4:staff:read，维护需要 m4:staff:write（V21 最小集：SYS_ADMIN / SYS_ANESTHESIOLOGIST）；
 * - 工号院内唯一且创建后不可改；userId 一人一档案；更新使用 version 乐观锁；
 * - 数据范围：ALL 全院 / DEPT 本科室 / SELF 仅本人（只读）；越权 403、越界 404；
 * - 姓名与证书号只用于本域展示，不写入日志与审计。
 */

const auth = useAuthStore()

const staff = ref<Staff[]>([])
const departments = ref<DepartmentOption[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)
const keyword = ref('')
const employmentStatus = ref('ACTIVE')
const total = ref(0)
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)

const emptyQualification = (): StaffQualificationInput => ({
  qualificationType: 'PRACTICE_LICENSE',
  certificateNo: '',
  issuingAuthority: '',
  validFrom: '',
  validTo: undefined,
})

const form = reactive({
  userId: '',
  staffNo: '',
  name: '',
  departmentId: '',
  title: '',
  jobGrade: '',
  employmentStatus: 'ACTIVE' as 'ACTIVE' | 'INACTIVE',
  specialtiesText: '',
  qualification: emptyQualification(),
  version: 1,
})

const canWrite = computed(() => auth.hasPermission('m4:staff:write'))
const isEditing = computed(() => editingId.value !== null)
const errorTitle = computed(() => {
  if (!error.value) return ''
  if (error.value.status === 403) return '无权访问人员档案或超出数据范围（m4:staff:read/write）'
  if (error.value.status === 409) return '工号/关联用户重复，或档案已被他人修改，请刷新后重试'
  if (error.value.status === 404) return '人员档案不存在或超出数据范围'
  if (error.value.status === 422) return '科室不存在/已停用或业务规则拒绝'
  if (error.value.status === 400) return '人员档案字段校验失败（见下发明细）'
  return '人员档案操作失败'
})

function handleError(cause: unknown): void {
  error.value = cause instanceof ApiError ? cause : null
  ElMessage.error(cause instanceof ApiError ? cause.message : '请求失败，请稍后重试')
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const [list, deptList] = await Promise.all([
      listStaff({
        keyword: keyword.value.trim(),
        employmentStatus: employmentStatus.value,
        size: 50,
      }),
      listStaffDepartments(),
    ])
    staff.value = list.items
    total.value = list.total
    departments.value = deptList.items
  } catch (cause) {
    handleError(cause)
  } finally {
    loading.value = false
  }
}

function resetForm(): void {
  form.userId = ''
  form.staffNo = ''
  form.name = ''
  form.departmentId = departments.value[0]?.departmentId ?? ''
  form.title = ''
  form.jobGrade = ''
  form.employmentStatus = 'ACTIVE'
  form.specialtiesText = ''
  form.qualification = emptyQualification()
  form.version = 1
}

function openCreate(): void {
  editingId.value = null
  resetForm()
  dialogVisible.value = true
}

function openEdit(item: Staff): void {
  editingId.value = item.staffId
  form.staffNo = item.staffNo
  form.userId = item.userId ?? ''
  form.name = item.name
  form.departmentId = item.departmentId
  form.title = item.title
  form.jobGrade = item.jobGrade ?? ''
  form.employmentStatus = item.employmentStatus
  form.specialtiesText = item.specialties.join('，')
  const first = item.qualifications[0]
  form.qualification = first
    ? {
        qualificationType: first.qualificationType,
        certificateNo: first.certificateNo,
        issuingAuthority: first.issuingAuthority,
        validFrom: first.validFrom,
        validTo: first.validTo,
      }
    : emptyQualification()
  form.version = item.version
  dialogVisible.value = true
}

function specialties(): string[] {
  return form.specialtiesText
    .split(/[，,]/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

function qualifications(): StaffQualificationInput[] {
  const qualification = form.qualification
  if (!qualification.certificateNo.trim() || !qualification.validFrom) {
    return []
  }
  return [qualification]
}

async function submit(): Promise<void> {
  if (!form.name.trim() || !form.title.trim() || !form.departmentId) {
    ElMessage.warning('姓名、职称与科室不能为空')
    return
  }
  if (!isEditing.value && !/^[A-Za-z0-9._-]{1,32}$/.test(form.staffNo.trim())) {
    ElMessage.warning('工号只能包含字母、数字、点、下划线与连字符（1–32）')
    return
  }
  saving.value = true
  error.value = null
  try {
    if (isEditing.value && editingId.value) {
      const payload: UpdateStaffRequest = {
        name: form.name.trim(),
        departmentId: form.departmentId,
        siteId: undefined,
        title: form.title.trim(),
        jobGrade: form.jobGrade.trim() || undefined,
        employmentStatus: form.employmentStatus,
        specialties: specialties(),
        qualifications: qualifications(),
        version: form.version,
      }
      await updateStaff(editingId.value, payload)
      ElMessage.success('人员档案已更新（版本 +1）')
    } else {
      const payload: CreateStaffRequest = {
        userId: form.userId.trim() || undefined,
        staffNo: form.staffNo.trim(),
        name: form.name.trim(),
        departmentId: form.departmentId,
        title: form.title.trim(),
        jobGrade: form.jobGrade.trim() || undefined,
        employmentStatus: form.employmentStatus,
        specialties: specialties(),
        qualifications: qualifications(),
      }
      await createStaff(payload)
      ElMessage.success('人员档案已创建')
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
  <section class="staff-list">
    <header class="page-header">
      <div>
        <h1>人员档案</h1>
        <p class="hint">
          麻醉医师/护士档案（工号唯一、职称/职级、执业资质、专长）；离职后不得执业，档案只读历史。
        </p>
      </div>
      <div class="actions">
        <el-input
          v-model="keyword"
          data-testid="staff-keyword"
          placeholder="工号/姓名/专长"
          clearable
          style="width: 200px"
          @keyup.enter="load"
        />
        <el-select
          v-model="employmentStatus"
          data-testid="staff-status"
          style="width: 120px"
          @change="load"
        >
          <el-option label="在职" value="ACTIVE" />
          <el-option label="离职/停用" value="INACTIVE" />
          <el-option label="全部" value="" />
        </el-select>
        <el-button data-testid="staff-refresh" @click="load">查询</el-button>
        <el-button v-if="canWrite" type="primary" data-testid="staff-create" @click="openCreate">
          新建档案
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
      data-testid="staff-error"
    />

    <el-table
      v-loading="loading"
      :data="staff"
      data-testid="staff-table"
      border
      style="width: 100%"
    >
      <el-table-column prop="staffNo" label="工号" width="150" />
      <el-table-column prop="name" label="姓名" min-width="140" />
      <el-table-column prop="departmentName" label="科室" min-width="130" />
      <el-table-column prop="title" label="职称" width="110" />
      <el-table-column prop="jobGrade" label="职级" width="90" />
      <el-table-column label="在职状态" width="110">
        <template #default="{ row }">
          <el-tag :type="row.employmentStatus === 'ACTIVE' ? 'success' : 'info'" size="small">
            {{ employmentStatusLabels[row.employmentStatus] ?? row.employmentStatus }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="资质" width="100">
        <template #default="{ row }">{{ row.qualifications.length }} 项</template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="70" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button
            v-if="canWrite"
            link
            type="primary"
            data-testid="staff-edit"
            @click="openEdit(row)"
          >
            编辑
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <span data-testid="staff-empty">暂无人员档案</span>
      </template>
    </el-table>
    <p class="hint" data-testid="staff-total">共 {{ total }} 条</p>

    <el-dialog
      v-model="dialogVisible"
      :title="isEditing ? '编辑人员档案' : '新建人员档案'"
      width="620px"
    >
      <el-form label-width="100px" data-testid="staff-form">
        <el-form-item label="工号">
          <el-input v-model="form.staffNo" data-testid="staff-no" :disabled="isEditing" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="form.name" data-testid="staff-name" />
        </el-form-item>
        <el-form-item label="科室">
          <el-select
            v-model="form.departmentId"
            data-testid="staff-dept"
            filterable
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
        <el-form-item label="职称">
          <el-input v-model="form.title" data-testid="staff-title" />
        </el-form-item>
        <el-form-item label="职级">
          <el-input v-model="form.jobGrade" data-testid="staff-job-grade" />
        </el-form-item>
        <el-form-item label="在职状态">
          <el-select v-model="form.employmentStatus" style="width: 100%">
            <el-option label="在职" value="ACTIVE" />
            <el-option label="离职/停用" value="INACTIVE" />
          </el-select>
        </el-form-item>
        <el-form-item label="专长">
          <el-input
            v-model="form.specialtiesText"
            data-testid="staff-specialties"
            placeholder="多个专长用中文逗号分隔"
          />
        </el-form-item>
        <el-form-item label="资质类型">
          <el-select v-model="form.qualification.qualificationType" style="width: 100%">
            <el-option
              v-for="(label, value) in qualificationTypeLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="证书编号">
          <el-input v-model="form.qualification.certificateNo" data-testid="staff-cert-no" />
        </el-form-item>
        <el-form-item label="签发机构">
          <el-input
            v-model="form.qualification.issuingAuthority"
            data-testid="staff-cert-authority"
          />
        </el-form-item>
        <el-form-item label="有效期起">
          <el-input
            v-model="form.qualification.validFrom"
            data-testid="staff-valid-from"
            placeholder="YYYY-MM-DD"
          />
        </el-form-item>
        <el-form-item label="有效期止">
          <el-input
            v-model="form.qualification.validTo"
            data-testid="staff-valid-to"
            placeholder="YYYY-MM-DD（留空表示长期有效）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="staff-submit" @click="submit">
          保存
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.staff-list {
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
