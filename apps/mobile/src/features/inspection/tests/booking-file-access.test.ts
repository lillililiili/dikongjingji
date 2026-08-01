import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  completeBookingAuthenticationRecovery,
  configureBookingAuthContext,
} from '../application/service-flow/booking-auth-context'
import { createBookingFileHashPort } from '../application/service-flow/booking-crypto'
import { uniBookingFileAccessPort } from '../data/service-flow/booking-file-access'
import { ApiRequestError, inspectionBookingApi } from '../data/service-flow/uni-booking-http-client'

const FILE_PATH = '_doc/attachment.jpg'
const FILE_BYTES = new Uint8Array([0, 1, 2, 3, 254, 255])
const FILE_DATA_URL = 'data:image/jpeg;base64,AAECA/7/'
const FILE_SHA256 = '7ea646958715ed687aa9ac2f5d785feb1a93411f4f25fdd6c7fcc6ab07fdf0e3'
const bookingFileHashPort = createBookingFileHashPort(uniBookingFileAccessPort)

type ResolveFile = (path: string, success: (entry: unknown) => void, fail: () => void) => void

function installAppPlusFileReader(options: { resolveFile?: ResolveFile; dataUrl?: string } = {}): void {
  class MockFileReader {
    result: string | null = null
    onloadend: (() => void) | null = null
    readAsDataURL(): void { this.result = options.dataUrl ?? FILE_DATA_URL; this.onloadend?.() }
  }
  vi.stubGlobal('plus', {
    io: {
      resolveLocalFileSystemURL: options.resolveFile ?? ((_path, success) => success({ file: (resolve: (file: unknown) => void) => resolve({}) })),
      FileReader: MockFileReader,
    },
  })
}

describe('App-Plus reservation attachment file access', () => {
  beforeEach(() => {
    vi.stubGlobal('uni', { getFileSystemManager: () => undefined, getStorageSync: () => null, setStorageSync: () => undefined })
    configureBookingAuthContext({ getAccessToken: () => 'customer-token', getPrincipalId: () => 'customer-1' })
    vi.stubEnv('VITE_INSPECTION_API_BASE_URL', 'http://127.0.0.1:8080')
    vi.stubEnv('VITE_INSPECTION_ALLOW_INSECURE_LOCAL_API', 'true')
  })
  afterEach(() => { completeBookingAuthenticationRecovery(); vi.unstubAllGlobals(); vi.unstubAllEnvs() })

  it('reads App-Plus fallback bytes for SHA-256 when the uni file manager is unavailable', async () => {
    installAppPlusFileReader()
    await expect(bookingFileHashPort.sha256(FILE_PATH)).resolves.toBe(FILE_SHA256)
  })

  it('rejects an unreadable App-Plus path without manufacturing a hash', async () => {
    installAppPlusFileReader({ resolveFile: (_path, _success, fail) => fail() })
    await expect(bookingFileHashPort.sha256(FILE_PATH)).rejects.toThrow('无法读取待上传附件')
  })

  it('rejects a malformed App-Plus data URL without manufacturing a hash', async () => {
    installAppPlusFileReader({ dataUrl: 'not-a-data-url' })
    await expect(bookingFileHashPort.sha256(FILE_PATH)).rejects.toThrow('附件数据格式错误')
  })

  it('sends the App-Plus fallback bytes in the raw presigned PUT body', async () => {
    installAppPlusFileReader()
    let putBody: unknown
    let putHeaders: Record<string, string> | undefined
    vi.stubGlobal('uni', {
      getFileSystemManager: () => undefined, getStorageSync: () => null, setStorageSync: () => undefined,
      request: (input: { url: string; method: string; data?: unknown; header?: Record<string, string>; success: (response: { statusCode: number; data: unknown; header: Record<string, string> }) => void }) => {
        if (input.method === 'PUT') { putBody = input.data; putHeaders = input.header; input.success({ statusCode: 200, data: null, header: { etag: 'real-etag' } }); return }
        input.success({ statusCode: 200, data: input.url.endsWith('/complete') ? { id: '70000000-0000-4000-8000-000000000001', status: 'SCANNING', scanFailureReason: null } : { fileId: '70000000-0000-4000-8000-000000000001', uploadUrl: 'https://storage.example.com/upload', requiredHeaders: { 'x-amz-meta-checksum': 'real-checksum' } }, header: {} })
      },
    })
    const uploaded = await inspectionBookingApi.uploadReservationAttachment({ reservationId: '80000000-0000-4000-8000-000000000001', originalName: 'attachment.jpg', contentType: 'image/jpeg', sizeBytes: FILE_BYTES.length, sha256: FILE_SHA256, filePath: FILE_PATH, idempotencyKey: '90000000-0000-4000-8000-000000000001' })
    expect(uploaded.status).toBe('SCANNING')
    expect(putBody).toBeInstanceOf(ArrayBuffer)
    expect(new Uint8Array(putBody as ArrayBuffer)).toEqual(FILE_BYTES)
    expect(putHeaders).toEqual({ 'x-amz-meta-checksum': 'real-checksum' })
  })

  it('keeps the upload-unavailable error when neither file reader exists', async () => {
    vi.stubGlobal('uni', {
      getFileSystemManager: () => undefined, getStorageSync: () => null, setStorageSync: () => undefined,
      request: (input: { success: (response: { statusCode: number; data: unknown; header: Record<string, string> }) => void }) => input.success({ statusCode: 200, data: { fileId: '70000000-0000-4000-8000-000000000001', uploadUrl: 'https://storage.example.com/upload', requiredHeaders: {} }, header: {} }),
    })
    await expect(inspectionBookingApi.uploadReservationAttachment({ reservationId: '80000000-0000-4000-8000-000000000001', originalName: 'attachment.jpg', contentType: 'image/jpeg', sizeBytes: FILE_BYTES.length, sha256: FILE_SHA256, filePath: FILE_PATH, idempotencyKey: '90000000-0000-4000-8000-000000000001' })).rejects.toMatchObject({ code: 'FILE_UPLOAD_UNAVAILABLE' } satisfies Partial<ApiRequestError>)
  })
})
