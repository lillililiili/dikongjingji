import { describe, expect, it } from 'vitest'

import * as inspectionRoutes from '../navigation/inspection-routes'
import pagesSource from '../../../pages.json?raw'
import manifestSource from '../../../manifest.json?raw'
import bookingPageSource from '../presentation/booking/pages/InspectionBookingPage.vue?raw'
import submittedPageSource from '../presentation/booking/pages/InspectionBookingSubmittedPage.vue?raw'
import bookingApiSource from '../data/service-flow/uni-booking-http-client.ts?raw'
import {
  createBookingLocationSelection,
  createBookingIdempotencyKey,
  createEmptyBookingDraft,
  createReservationPayload,
  validateBookingDraft,
  type BookingServiceSnapshot,
} from '../domain/service-flow/booking'
import {
  createInspectionBookingApi,
  type BookingHttpClient,
} from '../data/service-flow/inspection-booking-api'
import { sha256 } from '../application/service-flow/booking-crypto'

const SERVICE_ID = '40000000-0000-4000-8000-000000000001'

describe('F04 booking navigation', () => {
  it('opens the booking page with only the stable service ID', () => {
    const routes = inspectionRoutes as typeof inspectionRoutes & {
      buildInspectionBookingUrl(serviceId: string): string
    }

    expect(routes.buildInspectionBookingUrl(SERVICE_ID)).toBe(
      `/pages/service/inspection/booking?serviceId=${SERVICE_ID}`,
    )
  })

  it('serializes the city so the booking page can recover after refresh', () => {
    expect(
      inspectionRoutes.buildInspectionBookingUrl(SERVICE_ID, '370500'),
    ).toBe(
      `/pages/service/inspection/booking?serviceId=${SERVICE_ID}&cityCode=370500`,
    )
  })

  it('opens the submitted page with only the stable reservation ID', () => {
    expect(
      inspectionRoutes.buildInspectionBookingSubmittedUrl(
        '50000000-0000-4000-8000-000000000001',
      ),
    ).toBe(
      '/pages/service/inspection/booking-submitted?reservationId=50000000-0000-4000-8000-000000000001',
    )
  })

  it('registers both booking routes with custom navigation bars', () => {
    const pages = JSON.parse(pagesSource) as { pages: Array<{ path: string; style?: Record<string, string> }> }
    for (const path of [
      'pages/service/inspection/booking',
      'pages/service/inspection/booking-submitted',
    ]) {
      expect(pages.pages.find((page) => page.path === path)?.style?.navigationStyle).toBe('custom')
    }
  })
})

describe('F04 booking form boundary', () => {
  const service: BookingServiceSnapshot = {
    serviceId: SERVICE_ID,
    categoryName: '输电巡检',
    title: '输电线路无人机巡检',
    summary: '杆塔精细巡检',
    coverImageUrl: 'https://cdn.example.com/cover.webp',
    cityCode: '370500',
    inspectionItems: [{ code: 'TOWER', label: '杆塔本体巡检' }],
    priceMode: 'STARTING_PRICE',
    priceAmountMinor: 69900,
    priceUnitLabel: '公里',
    priceLabel: '¥699/公里起',
  }

  it('requires every mandatory field and does not preselect inspection items', () => {
    const draft = createEmptyBookingDraft()

    expect(draft.inspectionItemCodes).toEqual([])
    expect(validateBookingDraft(draft)).toEqual([
      '请填写巡检地点',
      '请填写有效的线路长度',
      '请选择期望巡检日期',
      '请至少选择一个巡检项目',
      '请填写联系人',
      '请填写正确的手机号',
      '请阅读并同意服务协议',
    ])
  })

  it('uses a deterministic cross-platform SHA-256 implementation instead of Web Crypto globals', () => {
    expect(sha256(new TextEncoder().encode('abc'))).toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    )
  })

  it('builds the create request without client-side service title or cover snapshot', () => {
    const payload = createReservationPayload(service, {
      ...createEmptyBookingDraft(),
      locationAddress: '东营市垦利区黄河路 88 号',
      locationLongitude: 118.674767,
      locationLatitude: 37.434751,
      lineLengthKm: '12.3',
      preferredServiceDate: '2026-08-02',
      inspectionItemCodes: ['TOWER'],
      contactName: '王先生',
      contactMobile: '13800138000',
      agreementAccepted: true,
    })

    expect(payload).toMatchObject({
      serviceId: SERVICE_ID,
      cityCode: '370500',
      lineLengthMeters: 12300,
      inspectionItemCodes: ['TOWER'],
      agreementAccepted: true,
    })
    expect(payload).not.toHaveProperty('serviceTitle')
    expect(payload).not.toHaveProperty('serviceCoverUrl')
  })

  it('normalizes a native map selection and submits its coordinates', () => {
    const location = createBookingLocationSelection({
      name: '黄河路 88 号',
      address: '山东省东营市东营区黄河路',
      longitude: 118.674767,
      latitude: 37.434751,
    })
    const payload = createReservationPayload(service, {
      ...createEmptyBookingDraft(),
      locationAddress: location.address,
      locationLongitude: location.longitude,
      locationLatitude: location.latitude,
      lineLengthKm: '12.3',
      preferredServiceDate: '2026-08-02',
      inspectionItemCodes: ['TOWER'],
      contactName: '王先生',
      contactMobile: '13800138000',
      agreementAccepted: true,
    })

    expect(payload.location).toEqual({
      address: '山东省东营市东营区黄河路 黄河路 88 号',
      longitude: 118.674767,
      latitude: 37.434751,
    })
  })

  it('rejects a typed address when map coordinates are unavailable', () => {
    const draft = {
      ...createEmptyBookingDraft(),
      locationAddress: '东营市黄河路 88 号',
      lineLengthKm: '12.3',
      preferredServiceDate: '2026-08-02',
      inspectionItemCodes: ['TOWER'],
      contactName: '王先生',
      contactMobile: '13800138000',
      agreementAccepted: true,
    }

    expect(validateBookingDraft(draft)).toEqual(['请重新选择巡检地点'])
  })

  it('rejects a location when only one coordinate is present', () => {
    const draft = {
      ...createEmptyBookingDraft(),
      locationAddress: '东营市黄河路 88 号',
      locationLongitude: 118.674767,
      lineLengthKm: '12.3',
      preferredServiceDate: '2026-08-02',
      inspectionItemCodes: ['TOWER'],
      contactName: '王先生',
      contactMobile: '13800138000',
      agreementAccepted: true,
    }

    expect(validateBookingDraft(draft)).toEqual(['请重新选择巡检地点'])
  })

  it('rejects an invalid coordinate returned by the native map picker', () => {
    expect(() => createBookingLocationSelection({
      name: '黄河路 88 号',
      address: '山东省东营市东营区黄河路',
      longitude: 181,
      latitude: 37.434751,
    })).toThrowError('INVALID_BOOKING_LOCATION')
  })

  it('rejects a native map selection without a usable address', () => {
    expect(() => createBookingLocationSelection({
      name: ' ',
      address: '',
      longitude: 118.674767,
      latitude: 37.434751,
    })).toThrowError('INVALID_BOOKING_LOCATION')
  })

  it('keeps one generated idempotency key stable for a retry attempt', () => {
    const key = createBookingIdempotencyKey(
      () => '60000000-0000-4000-8000-000000000001',
    )

    expect(key).toBe('60000000-0000-4000-8000-000000000001')
    expect(key).toBe(key)
  })

  it('rejects more than nine attachments before creating a reservation', () => {
    const draft = {
      ...createEmptyBookingDraft(),
      locationAddress: '东营市垦利区黄河路 88 号',
      lineLengthKm: '1',
      preferredServiceDate: '2026-08-02',
      inspectionItemCodes: ['TOWER'],
      contactName: '王先生',
      contactMobile: '13800138000',
      agreementAccepted: true,
      attachments: Array.from({ length: 10 }, (_, index) => ({
        localId: String(index), filePath: `/tmp/${index}.jpg`, originalName: `${index}.jpg`, contentType: 'image/jpeg', sizeBytes: 1, sha256: 'a'.repeat(64), status: 'READY' as const, fileId: String(index),
      })),
    }

    expect(validateBookingDraft(draft)).toContain('附件最多上传 9 个')
  })
})

describe('F04 booking API workflow', () => {
  it('uses the reservation endpoints and completes an upload after presigned PUT', async () => {
    const calls: Array<{ url: string; method: string; headers?: Record<string, string> }> = []
    const http: BookingHttpClient = {
      request: async <T,>(input: {
        url: string
        method: 'GET' | 'POST'
        data?: unknown
        headers?: Record<string, string>
      }) => {
        calls.push(input)
        if (input.url.endsWith('/upload-intents')) {
          return {
            fileId: '70000000-0000-4000-8000-000000000001',
            uploadUrl: 'https://storage.example.com/upload',
            requiredHeaders: { 'x-amz-meta-checksum': 'abc' },
          } as T
        }
        if (input.url.includes('/complete')) {
          return {
            id: '70000000-0000-4000-8000-000000000001',
            status: 'SCANNING',
            scanFailureReason: null,
          } as T
        }
        return { id: '50000000-0000-4000-8000-000000000001' } as T
      },
      putFile: async (input) => {
        calls.push({ url: input.url, method: 'PUT', headers: input.headers })
        return { etag: 'etag-1' }
      },
    }
    const api = createInspectionBookingApi(http, {
      getAccessToken: () => 'customer-token',
    })

    const uploaded = await api.uploadReservationAttachment({
      reservationId: '80000000-0000-4000-8000-000000000001',
      originalName: 'route.jpg',
      contentType: 'image/jpeg',
      sizeBytes: 1024,
      sha256: 'a'.repeat(64),
      filePath: '/tmp/route.jpg',
      idempotencyKey: '90000000-0000-4000-8000-000000000001',
    })

    expect(uploaded.status).toBe('SCANNING')
    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual([
      'POST /api/v1/inspection/file-objects/upload-intents',
      'PUT https://storage.example.com/upload',
      'POST /api/v1/inspection/file-objects/70000000-0000-4000-8000-000000000001/complete',
    ])
    expect(calls[0]?.headers).toMatchObject({
      Authorization: 'Bearer customer-token',
      'Idempotency-Key': '90000000-0000-4000-8000-000000000001',
    })
    expect(calls[1]?.headers).toEqual({ 'x-amz-meta-checksum': 'abc' })
  })
})

describe('F04 booking page contract', () => {
  it('opens the native map picker and stores its address and coordinates', () => {
    expect(bookingPageSource).toContain('uni.chooseLocation({')
    expect(bookingPageSource).toContain('createBookingLocationSelection(result)')
    expect(bookingPageSource).toContain('@tap="chooseInspectionLocation"')
    expect(bookingPageSource).toContain('正在打开地图…')
    expect(bookingPageSource).not.toContain('editable: true')
    expect(bookingPageSource).not.toContain('uni.getLocation({')
    expect(bookingPageSource).not.toContain('v-model="draft.locationAddress"')
  })

  it('configures AMap for the independently packaged Android acceptance build', () => {
    const manifest = JSON.parse(manifestSource) as {
      'app-plus': {
        distribute?: {
          sdkConfigs?: {
            maps?: { amap?: { appkey_android?: string } }
            geolocation?: { amap?: { appkey_android?: string } }
          }
        }
      }
    }
    const sdkConfigs = manifest['app-plus'].distribute?.sdkConfigs
    const mapKey = sdkConfigs?.maps?.amap?.appkey_android

    expect(mapKey).toMatch(/^[a-f0-9]{32}$/)
    expect(sdkConfigs?.geolocation?.amap?.appkey_android).toBe(mapKey)
  })

  it('uses the Android system media picker and describes pending files accurately', () => {
    const manifest = JSON.parse(manifestSource) as {
      'app-plus': { modules: Record<string, unknown> }
    }

    expect(bookingPageSource).toContain('uni.chooseMedia({')
    expect(bookingPageSource).toContain("mediaType: ['image']")
    expect(bookingPageSource).toContain('file.tempFilePath')
    expect(bookingPageSource).toContain('已选择，提交时上传')
    expect(bookingPageSource).not.toContain('uni.chooseImage({')
    expect(manifest['app-plus'].modules).toHaveProperty('Camera')
  })

  it('keeps the three-stage flow, safe-area footer and no preselected project', () => {
    expect(bookingPageSource).toContain('<BookingProgress :active="1"')
    expect(bookingPageSource).toContain('env(safe-area-inset-bottom)')
    expect(bookingPageSource).toContain('draft.value.inspectionItemCodes = selected.includes(code)')
    expect(bookingPageSource).toContain('提交预约')
  })

  it('matches the approved booking structure including customer service and PDF-capable attachments', () => {
    expect(bookingPageSource).toContain('客服')
    expect(bookingPageSource).toContain('chooseMessageFile')
    expect(bookingPageSource).toContain('图片、PDF，最多9个文件')
    expect(bookingPageSource).toContain('提交后由服务商评估方案并联系确认，暂不收取费用')
  })

  it('keeps all approved booking sections within the calibrated phone density', () => {
    expect(bookingPageSource).toContain('min-height:27px')
    expect(bookingPageSource).toContain('height:42px')
    expect(bookingPageSource).toContain(':deep(.booking-progress){padding:4px 24px 1px}')
    expect(bookingPageSource).toContain(':deep(.booking-service-card__image){width:90px;height:90px}')
  })

  it('keeps the approved footer structure and opens a published real plan', () => {
    expect(submittedPageSource).toContain('返回首页')
    expect(submittedPageSource).not.toContain('查看预约进度')
    expect(submittedPageSource).toContain('inspectionBookingApi.getReservation(reservationId)')
    expect(submittedPageSource).toContain('reservation.value?.currentPlanId')
    expect(submittedPageSource).toContain('buildInspectionPlanConfirmationUrl')
    expect(submittedPageSource).toContain('查看服务方案')
  })

  it('advances the submitted state to plan confirmation and renders DTO-backed details', () => {
    expect(submittedPageSource).toContain('<BookingProgress :active="2"')
    expect(submittedPageSource).toContain('待方案确认')
    expect(submittedPageSource).toContain('uni.setClipboardData')
    expect(submittedPageSource).toContain('reservation.inspectionItems')
    expect(submittedPageSource).toContain('reservation.contact.mobile')
  })

  it('uses a configured API base URL and request correlation header', () => {
    expect(bookingApiSource).toContain('VITE_INSPECTION_API_BASE_URL')
    expect(bookingApiSource).toContain("'X-Request-Id'")
    expect(bookingApiSource).toContain('ApiRequestError')
    expect(bookingApiSource).toContain('INSECURE_API_BASE_URL')
  })
})
