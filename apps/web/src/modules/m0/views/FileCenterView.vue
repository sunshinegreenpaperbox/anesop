<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../../api/http'
import {
  downloadFile,
  getFileMeta,
  listMyFiles,
  type FileBusinessType,
  type FileMeta,
  type FilePage,
} from '../api/platform'
import FileUploadPanel from '../components/FileUploadPanel.vue'

/**
 * 文件管理示例页（M8-M0-04）：演示上传/下载组件在业务页面的复用入口。
 *
 * - `FileUploadPanel` 通过 `business-type`/`business-id` 复用：排程页传 `SCHEDULE` + 台次标识，
 *   记录页传 `RECORD` + 记录标识；本页用下拉切换业务类型演示入口；
 * - 下载/元数据按 `m0:file:read` + 可见性（上传者 / SYS_ADMIN / 业务模块只读权限）鉴权；
 * - **不得存 PHI**（页面文案与 ADR-0024 一致）；病毒扫描为占位 EXT-07。
 */

const businessType = ref<FileBusinessType>('TEMPLATE')
const businessId = ref('')
const fileId = ref('')
const meta = ref<FileMeta | null>(null)
const loadingMeta = ref(false)
const error = ref('')
const uploaded = ref<FileMeta | null>(null)
const myFiles = ref<FilePage['items']>([])
const myMeta = ref<FilePage['meta']>({ page: 1, size: 20, total: 0, totalPages: 0 })
const loadingMyFiles = ref(false)

const businessTypes: { value: FileBusinessType; label: string }[] = [
  { value: 'TEMPLATE', label: '模板（TEMPLATE）' },
  { value: 'REPORT', label: '报表（REPORT）' },
  { value: 'ATTACHMENT', label: '附件（ATTACHMENT）' },
  { value: 'SCHEDULE', label: '排程附件（SCHEDULE）' },
  { value: 'RECORD', label: '记录附件（RECORD）' },
]

function onUploaded(value: FileMeta): void {
  uploaded.value = value
  fileId.value = value.fileId
  void loadMyFiles()
}

async function loadMyFiles(page = 1): Promise<void> {
  loadingMyFiles.value = true
  error.value = ''
  try {
    const data = await listMyFiles(page, myMeta.value.size)
    myFiles.value = data.items
    myMeta.value = data.meta
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '加载我的文件失败'
  } finally {
    loadingMyFiles.value = false
  }
}

async function downloadRow(row: FileMeta): Promise<void> {
  meta.value = row
  await download()
}

onMounted(() => {
  void loadMyFiles()
})

async function queryMeta(): Promise<void> {
  if (!fileId.value.trim()) {
    error.value = '请输入文件 ID'
    return
  }
  loadingMeta.value = true
  error.value = ''
  try {
    meta.value = await getFileMeta(fileId.value.trim())
  } catch (cause) {
    meta.value = null
    error.value = cause instanceof ApiError ? cause.message : '查询文件元数据失败'
  } finally {
    loadingMeta.value = false
  }
}

async function download(): Promise<void> {
  if (!meta.value) return
  try {
    const file = await downloadFile(meta.value.fileId)
    const url = URL.createObjectURL(file.blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = file.fileName
    anchor.click()
    URL.revokeObjectURL(url)
    ElMessage.success('下载已开始')
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '下载失败，请稍后重试'
  }
}
</script>

<template>
  <section class="files">
    <header class="files__header">
      <h1 class="files__title">文件管理</h1>
      <p class="files__subtitle">
        上传/下载非 PHI 文件（模板/报表/附件）。权限点 <code>m0:file:write</code> /
        <code>m0:file:read</code>；下载另按业务关联收紧（排程/记录/报表需对应模块只读权限
        <b>且业务对象在本人数据范围内</b>，超出范围返回 404；附件仅上传者与管理员）。
        展示名由服务端生成 <code>file-&lt;id&gt;.&lt;ext&gt;</code>，不保存客户端原始文件名。
        存储适配器：本地 FS（默认 <code>.cache/files</code>），MinIO 占位 EXT-06。
      </p>
    </header>

    <div class="files__reuse">
      <el-form inline>
        <el-form-item label="业务类型">
          <el-select v-model="businessType" class="files__select">
            <el-option
              v-for="item in businessTypes"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="业务关联 ID（可选）">
          <el-input v-model="businessId" placeholder="如台次/记录/报表标识" class="files__input" />
        </el-form-item>
      </el-form>
      <p class="files__hint">
        复用示例：排程页
        <code>&lt;FileUploadPanel business-type="SCHEDULE" :business-id="caseId" /&gt;</code>；
        记录页 <code>business-type="RECORD"</code>。本页下拉仅用于演示组件入口。
      </p>
    </div>

    <FileUploadPanel
      :business-type="businessType"
      :business-id="businessId"
      title="上传文件（示例页）"
      @uploaded="onUploaded"
    />

    <el-card class="files__card" shadow="never">
      <template #header> 我的文件（只显示当前账号上传；<code>m0:file:read</code>） </template>
      <el-table
        v-loading="loadingMyFiles"
        :data="myFiles"
        stripe
        data-testid="my-files-table"
        class="files__table"
      >
        <el-table-column prop="fileName" label="文件名（服务端生成）" min-width="260" />
        <el-table-column prop="contentType" label="类型" width="150" />
        <el-table-column prop="sizeBytes" label="大小（字节）" width="120" />
        <el-table-column prop="businessType" label="业务类型" width="130" />
        <el-table-column prop="businessId" label="业务关联" min-width="180">
          <template #default="{ row }">{{ row.businessId ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="上传时间" min-width="180" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="downloadRow(row)">下载</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <p class="files__hint">暂无上传记录。</p>
        </template>
      </el-table>
      <div v-if="myMeta.totalPages > 1" class="files__pager">
        <el-pagination
          layout="prev, pager, next"
          :current-page="myMeta.page"
          :page-size="myMeta.size"
          :total="myMeta.total"
          @current-change="(page: number) => loadMyFiles(page)"
        />
      </div>
    </el-card>

    <el-card class="files__card" shadow="never">
      <template #header>按文件 ID 查询与下载（演示鉴权）</template>
      <div class="files__query">
        <el-input v-model="fileId" placeholder="文件 ID（UUID）" class="files__input" />
        <el-button :loading="loadingMeta" @click="queryMeta">查询元数据</el-button>
        <el-button type="primary" :disabled="!meta" @click="download">下载</el-button>
      </div>

      <el-alert
        v-if="error"
        class="files__error"
        type="error"
        :closable="false"
        show-icon
        :title="error"
      />

      <el-descriptions v-if="meta" :column="2" border class="files__meta">
        <el-descriptions-item label="文件名">{{ meta.fileName }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ meta.contentType }}</el-descriptions-item>
        <el-descriptions-item label="大小">{{ meta.sizeBytes }} 字节</el-descriptions-item>
        <el-descriptions-item label="业务类型">{{ meta.businessType }}</el-descriptions-item>
        <el-descriptions-item label="SHA-256" :span="2">
          <span class="files__mono">{{ meta.sha256 }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="扫描状态">{{ meta.scanStatus }}</el-descriptions-item>
        <el-descriptions-item label="上传者">{{ meta.uploaderId }}</el-descriptions-item>
      </el-descriptions>

      <p v-if="uploaded" class="files__hint" data-testid="uploaded-hint">
        最近上传：{{ uploaded.fileName }}（{{ uploaded.fileId }}）
      </p>
    </el-card>
  </section>
</template>

<style scoped>
.files {
  display: flex;
  flex-direction: column;
  gap: var(--anesop-space-4);
}

.files__title {
  margin: 0;
  font-size: var(--anesop-font-size-xl);
  font-weight: var(--anesop-font-weight-semibold);
}

.files__subtitle {
  margin: var(--anesop-space-1) 0 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
}

.files__subtitle code,
.files__hint code {
  padding: 0 var(--anesop-space-1);
  font-family: var(--anesop-font-family-mono);
  background: var(--anesop-color-primary-soft);
  border-radius: var(--anesop-radius-sm);
}

.files__reuse {
  padding: var(--anesop-space-3);
  background: var(--anesop-color-primary-soft);
  border-radius: var(--anesop-radius-lg);
}

.files__hint {
  margin: 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
}

.files__select {
  width: 220px;
}

.files__input {
  width: 320px;
}

.files__card {
  border: 1px solid var(--anesop-color-border-light);
  border-radius: var(--anesop-radius-lg);
  box-shadow: var(--anesop-shadow-sm);
}

.files__query {
  display: flex;
  flex-wrap: wrap;
  gap: var(--anesop-space-2);
}

.files__error {
  margin-top: var(--anesop-space-2);
}

.files__meta {
  margin-top: var(--anesop-space-3);
}

.files__table {
  width: 100%;
}

.files__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--anesop-space-3);
}

.files__mono {
  font-family: var(--anesop-font-family-mono);
  font-size: var(--anesop-font-size-xs);
  word-break: break-all;
}
</style>
