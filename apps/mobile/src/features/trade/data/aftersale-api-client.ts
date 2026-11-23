import { ApiRequestError, publicRequest, resolvePublicApiUrl } from '@/shared/http/public-api-request'

import { createAftersaleApi, type AftersaleBinaryPort } from './aftersale-api'

function parseError(raw: unknown, status: number): ApiRequestError {
  let payload: { message?: unknown; code?: unknown; details?: unknown } | null = null
  try {
    payload = typeof raw === 'string' ? JSON.parse(raw) : raw as typeof payload
  } catch {
    payload = null
  }
  return new ApiRequestError(
    typeof payload?.message === 'string' ? payload.message : `请求失败（${status}）`,
    status,
    typeof payload?.code === 'string' ? payload.code : null,
    payload?.details && typeof payload.details === 'object' && !Array.isArray(payload.details)
      ? payload.details as Record<string, unknown>
      : null,
  )
}

// uni.uploadFile 把文件放在 multipart 的 file 字段；回来的 body 是字符串。
const binary: AftersaleBinaryPort = {
  async upload(input) {
    const url = await resolvePublicApiUrl(input.url)
    return new Promise((resolve, reject) => {
      uni.uploadFile({
        url,
        filePath: input.filePath,
        name: 'file',
        header: input.headers,
        success: (response) => {
          if (response.statusCode >= 200 && response.statusCode < 300) {
            try {
              resolve(typeof response.data === 'string' ? JSON.parse(response.data) : response.data)
            } catch {
              reject(new ApiRequestError('上传结果无法识别', response.statusCode, null))
            }
            return
          }
          reject(parseError(response.data, response.statusCode))
        },
        fail: () => reject(new ApiRequestError('网络连接失败，请稍后重试', 0, 'NETWORK_ERROR')),
      })
    })
  },
  // 凭证图片要登录才能读，<image> 自己带不上令牌：先带令牌读回二进制，再变成 data: 地址。
  async readImage(input) {
    const url = await resolvePublicApiUrl(input.url)
    return new Promise((resolve, reject) => {
      uni.request({
        url,
        method: 'GET',
        header: input.headers,
        responseType: 'arraybuffer',
        success: (response) => {
          if (response.statusCode < 200 || response.statusCode >= 300 || !(response.data instanceof ArrayBuffer)) {
            reject(new ApiRequestError('图片读取失败', response.statusCode, null))
            return
          }
          const headers = response.header as Record<string, string | undefined>
          const type = headers['content-type'] ?? headers['Content-Type'] ?? 'image/jpeg'
          resolve(`data:${type.split(';')[0]};base64,${uni.arrayBufferToBase64(response.data)}`)
        },
        fail: () => reject(new ApiRequestError('网络连接失败，请稍后重试', 0, 'NETWORK_ERROR')),
      })
    })
  },
}

export const aftersaleApi = createAftersaleApi(publicRequest, binary)
