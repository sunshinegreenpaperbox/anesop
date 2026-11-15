import type { components } from '@anesop/contracts/platform'
import { ApiError, getApiBaseUrl, http } from '../../../api/http'
import { getAccessToken } from '../../../api/session'

export type DictType = components['schemas']['DictType']
export type DictTypeList = components['schemas']['DictTypeList']
export type DictItem = components['schemas']['DictItem']
export type DictItemList = components['schemas']['DictItemList']
export type CreateDictItemRequest = components['schemas']['CreateDictItemRequest']
export type UpdateDictItemRequest = components['schemas']['UpdateDictItemRequest']
export type Message = components['schemas']['Message']
export type MessagePage = components['schemas']['MessagePage']
export type MessageReadAllResponse = components['schemas']['MessageReadAllResponse']
export type FileMeta = components['schemas']['FileMeta']
export type FilePage = components['schemas']['FilePage']
export type FileUploadRequest = components['schemas']['FileUploadRequest']
export type FileBusinessType = components['schemas']['FileBusinessType']

/** 上传白名单（与契约 platform.yaml / 后端 FileServiceImpl 一致；前端只是提前提示）。 */
export const ALLOWED_FILE_CONTENT_TYPES = [
  'application/pdf',
  'text/csv',
  'text/plain',
  'image/png',
  'image/jpeg',
] as const

/** 契约固定上限：20 MiB。 */
export const MAX_FILE_SIZE_BYTES = 20 * 1024 * 1024

/**
 * 字典接口（platform.yaml 0.2.0，M8-M0-04）。
 * 字典全院可读；维护（新增/更新/停用）仅 SYS_ADMIN。
 */
export function listDictTypes(): Promise<DictTypeList> {
  return http.get<DictTypeList>('/v1/dicts')
}

export function listDictItems(type: string, includeDisabled = false): Promise<DictItemList> {
  const query = new URLSearchParams()
  if (includeDisabled) query.set('includeDisabled', 'true')
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return http.get<DictItemList>(`/v1/dicts/${encodeURIComponent(type)}${suffix}`)
}

export function createDictItem(type: string, payload: CreateDictItemRequest): Promise<DictItem> {
  return http.post<DictItem>(`/v1/dicts/${encodeURIComponent(type)}/items`, payload)
}

export function updateDictItem(
  type: string,
  itemId: string,
  payload: UpdateDictItemRequest,
): Promise<DictItem> {
  return http.put<DictItem>(
    `/v1/dicts/${encodeURIComponent(type)}/items/${encodeURIComponent(itemId)}`,
    payload,
  )
}

export function disableDictItem(type: string, itemId: string): Promise<void> {
  return http.delete<void>(
    `/v1/dicts/${encodeURIComponent(type)}/items/${encodeURIComponent(itemId)}`,
  )
}

/** 消息中心：仅当前用户消息；unreadCount 恒为全部未读数（菜单角标）。 */
export function listMessages(page = 1, size = 20, unreadOnly = false): Promise<MessagePage> {
  const query = new URLSearchParams({
    page: String(page),
    size: String(size),
    unreadOnly: unreadOnly ? 'true' : 'false',
  })
  return http.get<MessagePage>(`/v1/messages?${query.toString()}`)
}

export function markMessageRead(messageId: string): Promise<Message> {
  return http.post<Message>(`/v1/messages/${encodeURIComponent(messageId)}/read`)
}

export function markAllMessagesRead(): Promise<MessageReadAllResponse> {
  return http.post<MessageReadAllResponse>('/v1/messages/read-all')
}

/** 上传文件（JSON + Base64；非 PHI，类型/大小白名单）。 */
export function uploadFile(payload: FileUploadRequest): Promise<FileMeta> {
  return http.post<FileMeta>('/v1/files', payload)
}

export function getFileMeta(fileId: string): Promise<FileMeta> {
  return http.get<FileMeta>(`/v1/files/${encodeURIComponent(fileId)}/meta`)
}

/** 我的文件：只返回当前用户上传（后端强制 uploader_id = 当前用户）。 */
export function listMyFiles(page = 1, size = 20): Promise<FilePage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  return http.get<FilePage>(`/v1/files?${query.toString()}`)
}

export interface DownloadedFile {
  blob: Blob
  fileName: string
  sha256: string
}

/**
 * 下载文件：走同源 fetch + Bearer（http 层只解析 JSON，二进制单独实现），
 * 非 2xx 统一抛 ApiError（与其它接口错误模型一致）。
 */
export async function downloadFile(fileId: string): Promise<DownloadedFile> {
  const token = getAccessToken()
  const base = `${getApiBaseUrl()}/v1/files/${encodeURIComponent(fileId)}`
  // 相对 base URL 在浏览器可直接 fetch；测试环境（jsdom/Node fetch）要求绝对 URL。
  const url = new URL(base, globalThis.location?.origin ?? 'http://localhost').toString()
  const response = await fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    credentials: 'same-origin',
  })
  if (!response.ok) {
    let code = `HTTP_${response.status}`
    let message = `下载失败（HTTP ${response.status}）`
    let traceId = ''
    try {
      const body = (await response.json()) as {
        code?: string
        message?: string
        traceId?: string
      }
      if (typeof body.code === 'string') code = body.code
      if (typeof body.message === 'string') message = body.message
      if (typeof body.traceId === 'string') traceId = body.traceId
    } catch {
      // 非 JSON 错误体：保留 HTTP 状态文案。
    }
    throw new ApiError({ status: response.status, code, message, traceId })
  }
  const disposition = response.headers.get('Content-Disposition') ?? ''
  return {
    blob: await response.blob(),
    fileName: parseFileName(disposition) ?? `${fileId}.bin`,
    sha256: response.headers.get('X-Content-Sha256') ?? '',
  }
}

/** 从 RFC 5987 `filename*=UTF-8''...` 响应头解析文件名；解析失败返回 null。 */
export function parseFileName(disposition: string): string | null {
  const match = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (!match) return null
  try {
    return decodeURIComponent(match[1] ?? '')
  } catch {
    return null
  }
}

/** File/Blob → 标准 Base64（去掉 data URL 前缀）；供上传表单使用。 */
export function readFileAsBase64(file: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = () => reject(new Error('读取文件失败'))
    reader.onload = () => {
      const result = typeof reader.result === 'string' ? reader.result : ''
      const comma = result.indexOf(',')
      resolve(comma >= 0 ? result.slice(comma + 1) : result)
    }
    reader.readAsDataURL(file)
  })
}
