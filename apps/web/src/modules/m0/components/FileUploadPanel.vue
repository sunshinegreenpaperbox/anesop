<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '../../../api/http'
import {
  ALLOWED_FILE_CONTENT_TYPES,
  MAX_FILE_SIZE_BYTES,
  downloadFile,
  readFileAsBase64,
  uploadFile,
  type FileBusinessType,
  type FileMeta,
} from '../api/platform'

/**
 * 文件上传/下载组件（M8-M0-04，契约 platform.yaml 0.2.0）。
 *
 * 复用入口：排程详情、麻醉记录单等页面可直接挂载本组件并传 `business-type`/`business-id`，
 * 例如 `<FileUploadPanel business-type="RECORD" :business-id="recordId" />`。上传/下载的
 * 权限点（m0:file:write / m0:file:read）与可见性由后端强制，组件只做提前提示。
 *
 * **PHI 禁令**：仅允许模板/报表/附件（PDF、CSV、TXT、PNG、JPEG，≤20 MiB）；禁止临床影像
 * （DICOM/视频）、身份证明与含患者标识的表格/截图。类型/大小在客户端先校验，服务端与库层
 * 再次校验（纵深防御）。
 */

const props = withDefaults(
  defineProps<{
    businessType?: FileBusinessType
    businessId?: string
    title?: string
  }>(),
  { businessType: 'TEMPLATE', businessId: '', title: '上传文件（非 PHI）' },
)

const emit = defineEmits<{ uploaded: [meta: FileMeta] }>()

const fileInput = ref<HTMLInputElement | null>(null)
const selected = ref<File | null>(null)
const contentType = ref('')
const uploading = ref(false)
const error = ref('')
const lastUploaded = ref<FileMeta | null>(null)

const businessTypeLabels: Record<FileBusinessType, string> = {
  TEMPLATE: '模板（TEMPLATE）',
  REPORT: '报表（REPORT）',
  ATTACHMENT: '附件（ATTACHMENT，仅本人与管理员）',
  SCHEDULE: '排程附件（SCHEDULE）',
  RECORD: '记录附件（RECORD）',
}

const accept = computed(() => ALLOWED_FILE_CONTENT_TYPES.join(','))

function contentTypeFor(file: File): string | null {
  if (
    ALLOWED_FILE_CONTENT_TYPES.includes(file.type as (typeof ALLOWED_FILE_CONTENT_TYPES)[number])
  ) {
    return file.type
  }
  const name = file.name.toLowerCase()
  if (name.endsWith('.csv')) return 'text/csv'
  if (name.endsWith('.pdf')) return 'application/pdf'
  if (name.endsWith('.txt')) return 'text/plain'
  if (name.endsWith('.png')) return 'image/png'
  if (name.endsWith('.jpg') || name.endsWith('.jpeg')) return 'image/jpeg'
  return null
}

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  error.value = ''
  lastUploaded.value = null
  if (!file) {
    selected.value = null
    return
  }
  if (file.size > MAX_FILE_SIZE_BYTES) {
    error.value = '文件大小超出上限（20MB），请压缩或拆分后再上传'
    selected.value = null
    input.value = ''
    return
  }
  const type = contentTypeFor(file)
  if (!type) {
    error.value = '文件类型不在允许范围（报表/模板/附件：PDF、CSV、TXT、PNG、JPEG）'
    selected.value = null
    input.value = ''
    return
  }
  selected.value = file
  contentType.value = type
}

async function upload(): Promise<void> {
  if (!selected.value) {
    error.value = '请先选择文件'
    return
  }
  uploading.value = true
  error.value = ''
  try {
    const contentBase64 = await readFileAsBase64(selected.value)
    const meta = await uploadFile({
      fileName: selected.value.name,
      contentType: contentType.value as FileMeta['contentType'],
      sizeBytes: selected.value.size,
      contentBase64,
      businessType: props.businessType,
      businessId: props.businessId || undefined,
    })
    lastUploaded.value = meta
    emit('uploaded', meta)
    ElMessage.success('文件已上传')
    selected.value = null
    if (fileInput.value) fileInput.value.value = ''
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '上传失败，请稍后重试'
  } finally {
    uploading.value = false
  }
}

async function download(meta: FileMeta): Promise<void> {
  try {
    const file = await downloadFile(meta.fileId)
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
  <el-card class="file-upload" shadow="never">
    <template #header>
      <div class="file-upload__header">
        <span class="file-upload__title">{{ title }}</span>
        <el-tag size="small" effect="plain">{{ businessTypeLabels[businessType] }}</el-tag>
      </div>
    </template>

    <p class="file-upload__hint">
      允许 PDF、CSV、TXT、PNG、JPEG，单文件 ≤20MB。禁止临床影像、身份证明与含患者标识的
      表格/截图；病毒扫描为占位（EXT-07），扫描接入前不得上传外部来源文件。
    </p>

    <div class="file-upload__row">
      <input
        ref="fileInput"
        type="file"
        :accept="accept"
        class="file-upload__input"
        data-testid="file-input"
        @change="onFileChange"
      />
      <el-button
        type="primary"
        :loading="uploading"
        :disabled="!selected"
        data-testid="file-upload-submit"
        @click="upload"
      >
        上传
      </el-button>
    </div>

    <p v-if="selected" class="file-upload__selected">
      已选择：{{ selected.name }}（{{ Math.ceil(selected.size / 1024) }} KB，{{ contentType }}）
    </p>

    <el-alert
      v-if="error"
      class="file-upload__error"
      type="error"
      :closable="false"
      show-icon
      :title="error"
    />

    <div v-if="lastUploaded" class="file-upload__result" data-testid="file-upload-result">
      <p class="file-upload__meta">
        已上传：{{ lastUploaded.fileName }} · {{ lastUploaded.sizeBytes }} 字节 · SHA-256
        {{ lastUploaded.sha256.slice(0, 12) }}… · 扫描状态
        {{ lastUploaded.scanStatus }}
      </p>
      <el-button link type="primary" @click="download(lastUploaded)">下载校验</el-button>
    </div>
  </el-card>
</template>

<style scoped>
.file-upload {
  border: 1px solid var(--anesop-color-border-light);
  border-radius: var(--anesop-radius-lg);
  box-shadow: var(--anesop-shadow-sm);
}

.file-upload__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--anesop-space-2);
}

.file-upload__title {
  font-weight: var(--anesop-font-weight-semibold);
}

.file-upload__hint {
  margin: 0 0 var(--anesop-space-3);
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
}

.file-upload__row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--anesop-space-2);
}

.file-upload__selected {
  margin: var(--anesop-space-2) 0 0;
  color: var(--anesop-color-text-secondary);
  font-size: var(--anesop-font-size-sm);
}

.file-upload__error {
  margin-top: var(--anesop-space-2);
}

.file-upload__result {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--anesop-space-2);
  margin-top: var(--anesop-space-2);
}

.file-upload__meta {
  margin: 0;
  font-family: var(--anesop-font-family-mono);
  font-size: var(--anesop-font-size-xs);
}
</style>
