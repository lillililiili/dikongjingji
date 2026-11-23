import type { ApiClient } from '@/core/http/api-client'

// 契约 contracts/openapi-ops-media.yaml 1.0.0：运营端素材上传与预览。

export type OpsMediaKind = 'IMAGE' | 'VIDEO'

export interface OpsMedia {
  url: string
  fileName: string
  kind: OpsMediaKind
  contentType: string
  sizeBytes: number
}

export const MEDIA_LIMITS = {
  imageBytes: 5 * 1024 * 1024,
  videoBytes: 50 * 1024 * 1024,
  imageTypes: ['image/jpeg', 'image/png', 'image/webp'],
  videoTypes: ['video/mp4'],
} as const

/** 上传前先在页面上挡一次（后端按文件头再判断一次）：返回问题说明，没问题返回 null。 */
export function mediaProblem(file: { type: string, size: number }, kind: OpsMediaKind): string | null {
  const types: readonly string[] = kind === 'IMAGE' ? MEDIA_LIMITS.imageTypes : MEDIA_LIMITS.videoTypes
  if (!types.includes(file.type)) return kind === 'IMAGE' ? '只能上传 JPEG、PNG、WebP 图片' : '只能上传 MP4 视频'
  const max = kind === 'IMAGE' ? MEDIA_LIMITS.imageBytes : MEDIA_LIMITS.videoBytes
  if (file.size <= 0) return '文件是空的'
  if (file.size > max) return kind === 'IMAGE' ? '图片不能超过 5 MB' : '视频不能超过 50 MB'
  return null
}

/** 本平台上传的素材地址末尾是 /ops-media/<uuid>.<扩展名>：按这个文件名带登录预览（开发环境的地址打不开）。 */
const UPLOADED = /\/ops-media\/([0-9a-f-]{36}\.(?:jpg|png|webp|mp4))$/

export function uploadedFileName(url: string): string | null {
  return UPLOADED.exec(url.trim())?.[1] ?? null
}

/** 受控地址：只收带主机名的 https 地址，最多 500 字（和后端 OpsText.httpsUrl 一致）。 */
export function isHttpsUrl(value: string): boolean {
  const text = value.trim()
  if (text.length === 0 || text.length > 500 || /\s/.test(text)) return false
  try {
    const url = new URL(text)
    return url.protocol === 'https:' && url.hostname !== '' && url.username === '' && url.password === ''
  } catch {
    return false
  }
}

export function createMediaApi(http: ApiClient) {
  return {
    upload(file: Blob, fileName: string): Promise<OpsMedia> {
      const form = new FormData()
      form.append('file', file, fileName)
      return http.request<OpsMedia>('POST', '/api/v1/ops/media', { form })
    },
    preview(fileName: string): Promise<{ blob: Blob, fileName: string | null }> {
      return http.download(`/api/v1/ops/media/${encodeURIComponent(fileName)}`)
    },
  }
}

export type MediaApi = ReturnType<typeof createMediaApi>
