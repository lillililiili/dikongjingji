import { describe, expect, it } from 'vitest'
import { parse } from '@vue/compiler-sfc'
import {
  ModuleKind,
  ScriptTarget,
  transpileModule,
} from 'typescript'

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
import {
  createLatestRequestCoordinator,
  runLatestRequest,
  shouldNavigateBackToInspectionOrderCenter,
} from '../application/service-flow/inspection-order-navigation'

const SERVICE_ID = '40000000-0000-4000-8000-000000000001'

type SubmittedReservation = {
  status: 'SUBMITTED' | 'PLAN_PUBLISHED' | 'CANCELLED'
  currentPlanId: string | null
  marker: string
}
type SubmittedPlan = {
  status: 'PUBLISHED' | 'CONFIRMED'
  serviceOrderId: string | null
  marker: string
}
type SubmittedCancellationResult =
  | { kind: 'dismissed' }
  | { kind: 'cancelled'; reservation: SubmittedReservation }
  | { kind: 'refresh-required' }
  | { kind: 'failed'; message: string }
type SubmittedBookingControllerFactory = (options: {
  getReservation(): SubmittedReservation | null
  setReservation(reservation: SubmittedReservation): void
  isCancelling(): boolean
  setCancelling(value: boolean): void
  requestCancellation(reservation: SubmittedReservation): Promise<SubmittedCancellationResult>
  loadReservation(): Promise<boolean>
  invalidateReservationLoad(): void
  showToast(title: string, icon: 'success' | 'none'): void
  returnOrders(): void
  openPlan(planId: string): void
}) => {
  cancelOrder(): Promise<void>
  handlePrimaryAction(): void
  reloadOnShow(): Promise<boolean>
}
type SubmittedReservationLoaderFactory = (options: {
  load(): Promise<SubmittedReservation>
  loadPlan?(planId: string): Promise<SubmittedPlan>
  setReservation(reservation: SubmittedReservation): void
  setPlan?(plan: SubmittedPlan | null): void
  setLoadError(message: string): void
  setLoading(loading: boolean): void
  getLoadError(error: unknown): string
}) => {
  loadReservation(): Promise<boolean>
  invalidate(): void
}

async function loadSubmittedBookingControllerFactories(): Promise<{
  createSubmittedBookingController: SubmittedBookingControllerFactory
  createSubmittedReservationLoader: SubmittedReservationLoaderFactory
  canCancelSubmittedBooking(
    reservation: SubmittedReservation | null,
    plan: SubmittedPlan | null,
    actionsReady: boolean,
  ): boolean
  getSubmittedBookingFooterMode(
    reservation: SubmittedReservation | null,
  ): 'CONTACT_AND_PRIMARY' | 'RETURN_ORDERS_ONLY'
  getSubmittedBookingHeaderTitle(
    reservation: SubmittedReservation | null,
    openedFromOrderList: boolean,
  ): '提交成功' | '预约详情'
}> {
  const parsed = parse(submittedPageSource, {
    filename: 'InspectionBookingSubmittedPage.vue',
  })
  expect(parsed.descriptor.script).not.toBeNull()
  if (!parsed.descriptor.script) {
    throw new Error('Submitted booking controller script is unavailable')
  }
  const output = transpileModule(parsed.descriptor.script.content, {
    compilerOptions: {
      esModuleInterop: true,
      module: ModuleKind.CommonJS,
      target: ScriptTarget.ES2020,
    },
  }).outputText
  const testModule: {
    exports: {
      createSubmittedBookingController?: SubmittedBookingControllerFactory
      createSubmittedReservationLoader?: SubmittedReservationLoaderFactory
      canCancelSubmittedBooking?: (
        reservation: SubmittedReservation | null,
        plan: SubmittedPlan | null,
        actionsReady: boolean,
      ) => boolean
      getSubmittedBookingFooterMode?: (
        reservation: SubmittedReservation | null,
      ) => 'CONTACT_AND_PRIMARY' | 'RETURN_ORDERS_ONLY'
      getSubmittedBookingHeaderTitle?: (
        reservation: SubmittedReservation | null,
        openedFromOrderList: boolean,
      ) => '提交成功' | '预约详情'
    }
  } = { exports: {} }
  const evaluate = new Function('require', 'module', 'exports', output)
  evaluate(() => { throw new Error('The submitted booking controller has no runtime dependencies') }, testModule, testModule.exports)
  const factory = testModule.exports.createSubmittedBookingController
  expect(factory).toBeTypeOf('function')
  if (!factory) throw new Error('Submitted booking controller is unavailable')
  const loaderFactory = testModule.exports.createSubmittedReservationLoader
  expect(loaderFactory).toBeTypeOf('function')
  if (!loaderFactory) throw new Error('Submitted reservation loader is unavailable')
  const canCancelSubmittedBooking = testModule.exports.canCancelSubmittedBooking
  const getSubmittedBookingFooterMode = testModule.exports.getSubmittedBookingFooterMode
  const getSubmittedBookingHeaderTitle = testModule.exports.getSubmittedBookingHeaderTitle
  expect(canCancelSubmittedBooking).toBeTypeOf('function')
  expect(getSubmittedBookingFooterMode).toBeTypeOf('function')
  expect(getSubmittedBookingHeaderTitle).toBeTypeOf('function')
  if (!canCancelSubmittedBooking || !getSubmittedBookingFooterMode || !getSubmittedBookingHeaderTitle) {
    throw new Error('Submitted booking state rules are unavailable')
  }
  return {
    createSubmittedBookingController: factory,
    createSubmittedReservationLoader: loaderFactory,
    canCancelSubmittedBooking,
    getSubmittedBookingFooterMode,
    getSubmittedBookingHeaderTitle,
  }
}

describe('F04 booking navigation', () => {
  it('keeps only the latest submitted-reservation response and detects order-center history', async () => {
    const coordinator = createLatestRequestCoordinator()
    let resolveFirst: ((value: string) => void) | undefined
    const first = runLatestRequest(
      coordinator,
      () => new Promise<string>((resolve) => { resolveFirst = resolve }),
    )
    const second = runLatestRequest(coordinator, async () => 'latest reservation')
    resolveFirst?.('stale reservation')

    expect(await second).toEqual({ status: 'success', value: 'latest reservation' })
    expect(await first).toEqual({ status: 'discarded' })
    expect(shouldNavigateBackToInspectionOrderCenter('pages/mine/inspection-orders')).toBe(true)
    expect(shouldNavigateBackToInspectionOrderCenter('pages/service/inspection/booking-submitted')).toBe(false)
    expect(shouldNavigateBackToInspectionOrderCenter(undefined)).toBe(false)
  })

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
      '请选择期望巡检时间',
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
      preferredServiceTime: {
        serviceDate: '2026-08-02',
        slotCode: 'MORNING',
        startTime: '09:00',
        endTime: '12:00',
      },
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
      preferredServiceTime: {
        serviceDate: '2026-08-02',
        slotCode: 'MORNING',
        startTime: '09:00',
        endTime: '12:00',
      },
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
      preferredServiceTime: {
        serviceDate: '2026-08-02',
        slotCode: 'MORNING' as const,
        startTime: '09:00',
        endTime: '12:00',
      },
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
      preferredServiceTime: {
        serviceDate: '2026-08-02',
        slotCode: 'MORNING' as const,
        startTime: '09:00',
        endTime: '12:00',
      },
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
      preferredServiceTime: {
        serviceDate: '2026-08-02',
        slotCode: 'MORNING' as const,
        startTime: '09:00',
        endTime: '12:00',
      },
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
    expect(bookingPageSource).toContain('createBookingImageAttachments(files')
    expect(bookingPageSource).toContain('showImagePickerError')
    expect(bookingPageSource).toContain('已选择，提交时上传')
    expect(bookingPageSource).not.toContain('uni.chooseImage({')
    expect(manifest['app-plus'].modules).toHaveProperty('Camera')
  })

  it('keeps the confirm-order flow, safe-area footer and no preselected project', () => {
    expect(bookingPageSource).not.toContain('<BookingProgress')
    expect(bookingPageSource).toContain('env(safe-area-inset-bottom)')
    expect(bookingPageSource).toContain('draft.value.inspectionItemCodes = selected.includes(code)')
    expect(bookingPageSource).toContain('提交预约')
    expect(bookingPageSource).toContain('<AppPageHeader title="预约服务"')
    expect(bookingPageSource).not.toContain('确认订单')
    expect(bookingPageSource).not.toContain('提交订单')
    const bookingRoute = (JSON.parse(pagesSource) as {
      pages: Array<{ path: string; style?: { navigationBarTitleText?: string } }>
    }).pages.find((page) => page.path === 'pages/service/inspection/booking')
    expect(bookingRoute?.style?.navigationBarTitleText).toBe('预约服务')
  })

  it('restores the approved expanded confirm-order hierarchy', () => {
    expect(bookingPageSource).toContain('<button class="inspection-booking__customer"')
    expect(bookingPageSource).toContain('<text class="booking-section__title">预约信息</text>')
    expect(bookingPageSource).toContain('<text class="booking-section__title">巡检项目（可多选）</text>')
    expect(bookingPageSource).toContain('<text class="booking-section__title">现场资料</text>')
    expect(bookingPageSource).toContain('<view class="inspection-booking__tip"')
    expect(bookingPageSource).not.toContain('class="booking-arrangement"')
    expect(bookingPageSource).not.toContain('class="booking-requirements-summary"')
    expect(bookingPageSource).not.toContain('requirementsExpanded')
  })

  it('keeps PDF-capable attachments and the no-charge platform guarantee', () => {
    expect(bookingPageSource).toContain('pickInspectionPdfDocuments')
    expect(bookingPageSource).toContain('图片、PDF，最多9个文件')
    expect(bookingPageSource).toContain('平台保障 · 方案确认前不收取费用')
  })

  it('reopens time selection without clearing the draft when the slot fills during submit', () => {
    expect(bookingPageSource).toContain("error.code === 'INS_TIME_SLOT_FULL'")
    expect(bookingPageSource).toContain("submitError.value = '该时段刚刚约满，请重新选择'")
    expect(bookingPageSource).toContain('isTimePickerVisible.value = true')
    expect(bookingPageSource).not.toContain('draft.value = createEmptyBookingDraft()')
  })

  it('uses the typed PDF picker and keeps picker failures visible', () => {
    expect(bookingPageSource).toContain('客服')
    expect(bookingPageSource).toContain('bookingDocumentPicker')
    expect(bookingPageSource).toContain('pickInspectionPdfDocuments')
    expect(bookingPageSource).toContain('await pickBookingPdfAttachments')
    expect(bookingPageSource).toContain('showDocumentPickerError')
    expect(bookingPageSource).toContain('releasePdfAttachments')
    expect(bookingPageSource).toContain('bookingDocumentPicker.releasePdfFiles')
    expect(bookingPageSource).toContain('isPageActive = true')
    expect(bookingPageSource).toContain('isPageActive = false')
    expect(bookingPageSource).toContain('isPageActive: () => isPageActive')
    expect(bookingPageSource).toContain('onUnload(() =>')
    expect(bookingPageSource).not.toContain('chooseMessageFile')
    expect(bookingPageSource).toContain('图片、PDF，最多9个文件')
    expect(bookingPageSource).toContain('平台保障 · 方案确认前不收取费用')
  })

  it('keeps all approved booking sections within the calibrated phone density', () => {
    expect(bookingPageSource).toContain('min-height:50px')
    expect(bookingPageSource).toContain('height:82px')
    expect(bookingPageSource).toContain(':deep(.booking-service-card__image){width:126px;height:116px}')
    expect(bookingPageSource).toContain('.booking-section{padding:17px 16px')
  })

  it('keeps the approved footer structure and opens a published real plan', () => {
    expect(submittedPageSource).toContain('返回订单')
    expect(submittedPageSource).not.toContain('查看预约进度')
    expect(submittedPageSource).toContain('inspectionBookingApi.getReservation(reservationId.value)')
    expect(submittedPageSource).toContain('currentReservation?.currentPlanId')
    expect(submittedPageSource).toContain('buildInspectionPlanConfirmationUrl')
    expect(submittedPageSource).toContain('查看服务方案')
  })

  it('shows the approved cancellation entry and a read-only cancelled state', () => {
    expect(submittedPageSource).toContain('取消订单')
    expect(submittedPageSource).toContain('requestInspectionOrderCancellation')
    expect(submittedPageSource).toContain('confirmInspectionOrderCancellation')
    expect(submittedPageSource).toContain("status === 'CANCELLED'")
    expect(submittedPageSource).toContain('订单已取消')
    expect(submittedPageSource).toContain("isCancelling ? '取消中…' : '取消订单'")
    expect(submittedPageSource).toContain('v-if="showCustomerContact"')
    expect(submittedPageSource).toContain('联系服务商')
    expect(submittedPageSource).not.toContain('退款成功')
  })

  it('advances the submitted state to plan confirmation and renders DTO-backed details', () => {
    expect(submittedPageSource).not.toContain('<BookingProgress')
    expect(submittedPageSource).toContain("if (isCancelled.value) return '订单已取消'")
    expect(submittedPageSource).toContain("isPlanPublished.value ? '方案已发布' : '需求已提交'")
    expect(submittedPageSource).toContain('等待服务商确认，方案确认前不收费')
    expect(submittedPageSource).toContain('进度可在“我的订单”查看')
    expect(submittedPageSource).toContain('uni.setClipboardData')
    expect(submittedPageSource).toContain('maskMobile(current.contact.mobile)')
  })

  it('uses a configured API base URL and request correlation header', () => {
    expect(bookingApiSource).toContain('VITE_INSPECTION_API_BASE_URL')
    expect(bookingApiSource).toContain("'X-Request-Id'")
    expect(bookingApiSource).toContain('ApiRequestError')
    expect(bookingApiSource).toContain('INSECURE_API_BASE_URL')
  })
})

describe('submitted booking cancellation controller', () => {
  it('allows cancellation only before the published plan is confirmed or creates an order', async () => {
    const { canCancelSubmittedBooking } = await loadSubmittedBookingControllerFactories()
    const submitted: SubmittedReservation = {
      status: 'SUBMITTED', currentPlanId: null, marker: 'submitted',
    }
    const planPublished: SubmittedReservation = {
      status: 'PLAN_PUBLISHED', currentPlanId: 'plan-1', marker: 'published',
    }

    expect(canCancelSubmittedBooking(submitted, null, true)).toBe(true)
    expect(canCancelSubmittedBooking(planPublished, {
      status: 'PUBLISHED', serviceOrderId: null, marker: 'open',
    }, true)).toBe(true)
    expect(canCancelSubmittedBooking(planPublished, {
      status: 'CONFIRMED', serviceOrderId: null, marker: 'confirmed',
    }, true)).toBe(false)
    expect(canCancelSubmittedBooking(planPublished, {
      status: 'PUBLISHED', serviceOrderId: 'order-1', marker: 'ordered',
    }, true)).toBe(false)
    expect(canCancelSubmittedBooking(planPublished, null, true)).toBe(false)
    expect(canCancelSubmittedBooking(submitted, null, false)).toBe(false)
  })

  it('keeps cancellation hidden when the current plan cannot be loaded', async () => {
    const {
      canCancelSubmittedBooking,
      createSubmittedReservationLoader,
    } = await loadSubmittedBookingControllerFactories()
    let reservation: SubmittedReservation | null = null
    let plan: SubmittedPlan | null = {
      status: 'PUBLISHED', serviceOrderId: null, marker: 'stale',
    }
    const loader = createSubmittedReservationLoader({
      load: async () => ({
        status: 'PLAN_PUBLISHED', currentPlanId: 'plan-1', marker: 'server',
      }),
      loadPlan: async () => { throw new Error('PLAN_LOAD_FAILED') },
      setReservation: (value) => { reservation = value },
      setPlan: (value) => { plan = value },
      setLoadError: () => undefined,
      setLoading: () => undefined,
      getLoadError: () => '预约记录加载失败',
    })

    expect(await loader.loadReservation()).toBe(true)
    expect(reservation).toMatchObject({ marker: 'server' })
    expect(plan).toBeNull()
    expect(canCancelSubmittedBooking(reservation, plan, true)).toBe(false)
  })

  it('does not let an older plan GET overwrite the latest confirmed state', async () => {
    const { createSubmittedReservationLoader } = await loadSubmittedBookingControllerFactories()
    let resolveOldPlan: ((value: SubmittedPlan) => void) | undefined
    let oldPlanStarted: (() => void) | undefined
    const oldPlanIsLoading = new Promise<void>((resolve) => { oldPlanStarted = resolve })
    let loadIndex = 0
    let reservation: SubmittedReservation | null = null
    let plan: SubmittedPlan | null = null
    const loader = createSubmittedReservationLoader({
      load: async () => ({
        status: 'PLAN_PUBLISHED',
        currentPlanId: loadIndex++ === 0 ? 'plan-old' : 'plan-latest',
        marker: loadIndex === 1 ? 'old' : 'latest',
      }),
      loadPlan: (planId) => {
        if (planId === 'plan-old') {
          oldPlanStarted?.()
          return new Promise((resolve) => { resolveOldPlan = resolve })
        }
        return Promise.resolve({
          status: 'CONFIRMED', serviceOrderId: 'order-1', marker: 'latest',
        })
      },
      setReservation: (value) => { reservation = value },
      setPlan: (value) => { plan = value },
      setLoadError: () => undefined,
      setLoading: () => undefined,
      getLoadError: () => '预约记录加载失败',
    })

    const oldRequest = loader.loadReservation()
    await oldPlanIsLoading
    const latestRequest = loader.loadReservation()
    await latestRequest
    resolveOldPlan?.({ status: 'PUBLISHED', serviceOrderId: null, marker: 'old' })
    await oldRequest

    expect(reservation).toMatchObject({ marker: 'latest' })
    expect(plan).toMatchObject({ status: 'CONFIRMED', marker: 'latest' })
  })

  it('renders a cancelled footer with only the return-orders action', async () => {
    const { getSubmittedBookingFooterMode } = await loadSubmittedBookingControllerFactories()

    expect(getSubmittedBookingFooterMode({
      status: 'CANCELLED', currentPlanId: 'plan-1', marker: 'cancelled',
    })).toBe('RETURN_ORDERS_ONLY')
    expect(getSubmittedBookingFooterMode({
      status: 'PLAN_PUBLISHED', currentPlanId: 'plan-1', marker: 'active',
    })).toBe('CONTACT_AND_PRIMARY')
  })

  it('titles the header 提交成功 only for a fresh submission not opened from the order list', async () => {
    const { getSubmittedBookingHeaderTitle } = await loadSubmittedBookingControllerFactories()
    const submitted: SubmittedReservation = {
      status: 'SUBMITTED', currentPlanId: null, marker: 'submitted',
    }

    expect(getSubmittedBookingHeaderTitle(null, false)).toBe('提交成功')
    expect(getSubmittedBookingHeaderTitle(submitted, false)).toBe('提交成功')
    expect(getSubmittedBookingHeaderTitle(submitted, true)).toBe('预约详情')
    expect(getSubmittedBookingHeaderTitle({
      status: 'PLAN_PUBLISHED', currentPlanId: 'plan-1', marker: 'published',
    }, false)).toBe('预约详情')
    expect(getSubmittedBookingHeaderTitle({
      status: 'CANCELLED', currentPlanId: null, marker: 'cancelled',
    }, false)).toBe('预约详情')
  })

  it('keeps loading true when an old GET finishes before the latest GET', async () => {
    const { createSubmittedReservationLoader } = await loadSubmittedBookingControllerFactories()
    let resolveOld: ((value: SubmittedReservation) => void) | undefined
    let resolveLatest: ((value: SubmittedReservation) => void) | undefined
    const requests = [
      new Promise<SubmittedReservation>((resolve) => { resolveOld = resolve }),
      new Promise<SubmittedReservation>((resolve) => { resolveLatest = resolve }),
    ]
    let requestIndex = 0
    let reservation: SubmittedReservation | null = null
    const loading: boolean[] = []
    const loader = createSubmittedReservationLoader({
      load: () => requests[requestIndex++]!,
      setReservation: (value) => { reservation = value },
      setLoadError: () => undefined,
      setLoading: (value) => { loading.push(value) },
      getLoadError: () => '预约记录加载失败',
    })

    const oldRequest = loader.loadReservation()
    const latestRequest = loader.loadReservation()
    resolveOld?.({ status: 'SUBMITTED', currentPlanId: null, marker: 'old' })
    await oldRequest

    expect(reservation).toBeNull()
    expect(loading.at(-1)).toBe(true)
    resolveLatest?.({ status: 'PLAN_PUBLISHED', currentPlanId: 'plan-2', marker: 'latest' })
    await latestRequest

    expect(reservation).toMatchObject({ marker: 'latest' })
    expect(loading.at(-1)).toBe(false)
  })

  it('invalidates an in-flight GET after cancellation and skips on-show reload while busy', async () => {
    const {
      createSubmittedBookingController: factory,
      createSubmittedReservationLoader,
    } = await loadSubmittedBookingControllerFactories()
    let resolveOld: ((value: SubmittedReservation) => void) | undefined
    let loadCalls = 0
    let current: SubmittedReservation | null = {
      status: 'SUBMITTED', currentPlanId: null, marker: 'initial',
    }
    let cancelling = false
    const loader = createSubmittedReservationLoader({
      load: () => {
        loadCalls += 1
        return new Promise((resolve) => { resolveOld = resolve })
      },
      setReservation: (value) => { current = value },
      setLoadError: () => undefined,
      setLoading: () => undefined,
      getLoadError: () => '预约记录加载失败',
    })
    const oldRequest = loader.loadReservation()
    const serverReservation: SubmittedReservation = {
      status: 'CANCELLED', currentPlanId: null, marker: 'server',
    }
    const controller = factory({
      getReservation: () => current,
      setReservation: (value) => { current = value },
      isCancelling: () => cancelling,
      setCancelling: (value) => { cancelling = value },
      requestCancellation: async () => ({ kind: 'cancelled', reservation: serverReservation }),
      loadReservation: loader.loadReservation,
      invalidateReservationLoad: loader.invalidate,
      showToast: () => undefined,
      returnOrders: () => undefined,
      openPlan: () => undefined,
    })

    await controller.cancelOrder()
    resolveOld?.({ status: 'PLAN_PUBLISHED', currentPlanId: 'stale-plan', marker: 'old' })
    await oldRequest

    expect(current).toBe(serverReservation)
    cancelling = true
    await controller.reloadOnShow()
    expect(loadCalls).toBe(1)
  })

  it('prevents a double cancel and adopts the server reservation unchanged', async () => {
    const { createSubmittedBookingController: factory } = await loadSubmittedBookingControllerFactories()
    let reservation: SubmittedReservation | null = {
      status: 'SUBMITTED', currentPlanId: null, marker: 'initial',
    }
    const serverReservation: SubmittedReservation = {
      status: 'CANCELLED', currentPlanId: null, marker: 'server',
    }
    let cancelling = false
    let cancelCalls = 0
    let resolveCancellation: ((result: SubmittedCancellationResult) => void) | undefined
    const controller = factory({
      getReservation: () => reservation,
      setReservation: (value) => { reservation = value },
      isCancelling: () => cancelling,
      setCancelling: (value) => { cancelling = value },
      requestCancellation: () => {
        cancelCalls += 1
        return new Promise((resolve) => { resolveCancellation = resolve })
      },
      loadReservation: async () => true,
      invalidateReservationLoad: () => undefined,
      showToast: () => undefined,
      returnOrders: () => undefined,
      openPlan: () => undefined,
    })

    const first = controller.cancelOrder()
    const second = controller.cancelOrder()
    expect(cancelCalls).toBe(1)
    expect(cancelling).toBe(true)
    resolveCancellation?.({ kind: 'cancelled', reservation: serverReservation })
    await Promise.all([first, second])

    expect(reservation).toBe(serverReservation)
    expect(cancelling).toBe(false)
  })

  it('releases the busy state when the confirmation path rejects', async () => {
    const { createSubmittedBookingController: factory } = await loadSubmittedBookingControllerFactories()
    const reservation: SubmittedReservation = {
      status: 'SUBMITTED', currentPlanId: null, marker: 'initial',
    }
    let current = reservation
    let cancelling = false
    const toasts: string[] = []
    const controller = factory({
      getReservation: () => current,
      setReservation: (value) => { current = value },
      isCancelling: () => cancelling,
      setCancelling: (value) => { cancelling = value },
      requestCancellation: async () => { throw new Error('CONFIRMATION_FAILED') },
      loadReservation: async () => true,
      invalidateReservationLoad: () => undefined,
      showToast: (title) => { toasts.push(title) },
      returnOrders: () => undefined,
      openPlan: () => undefined,
    })

    await controller.cancelOrder()

    expect(current).toBe(reservation)
    expect(cancelling).toBe(false)
    expect(toasts).toEqual(['取消失败，请检查网络后重试'])
  })

  it('does not announce an updated order when the required refresh fails', async () => {
    const { createSubmittedBookingController: factory } = await loadSubmittedBookingControllerFactories()
    const reservation: SubmittedReservation = {
      status: 'SUBMITTED', currentPlanId: null, marker: 'initial',
    }
    let current = reservation
    let cancelling = false
    const toasts: string[] = []
    const controller = factory({
      getReservation: () => current,
      setReservation: (value) => { current = value },
      isCancelling: () => cancelling,
      setCancelling: (value) => { cancelling = value },
      requestCancellation: async () => ({ kind: 'refresh-required' }),
      loadReservation: async () => false,
      invalidateReservationLoad: () => undefined,
      showToast: (title) => { toasts.push(title) },
      returnOrders: () => undefined,
      openPlan: () => undefined,
    })

    await controller.cancelOrder()

    expect(current).toBe(reservation)
    expect(toasts).toEqual([])
    expect(cancelling).toBe(false)
  })

  it('keeps cancellation hidden when a conflict refresh discovers a confirmed plan', async () => {
    const {
      canCancelSubmittedBooking,
      createSubmittedBookingController: factory,
      createSubmittedReservationLoader,
    } = await loadSubmittedBookingControllerFactories()
    let reservation: SubmittedReservation | null = {
      status: 'PLAN_PUBLISHED', currentPlanId: 'plan-1', marker: 'initial',
    }
    let plan: SubmittedPlan | null = {
      status: 'PUBLISHED', serviceOrderId: null, marker: 'initial',
    }
    let cancelling = false
    const loader = createSubmittedReservationLoader({
      load: async () => ({
        status: 'PLAN_PUBLISHED', currentPlanId: 'plan-1', marker: 'refreshed',
      }),
      loadPlan: async () => ({
        status: 'CONFIRMED', serviceOrderId: 'order-1', marker: 'refreshed',
      }),
      setReservation: (value) => { reservation = value },
      setPlan: (value) => { plan = value },
      setLoadError: () => undefined,
      setLoading: () => undefined,
      getLoadError: () => '预约记录加载失败',
    })
    const controller = factory({
      getReservation: () => reservation,
      setReservation: (value) => { reservation = value },
      isCancelling: () => cancelling,
      setCancelling: (value) => { cancelling = value },
      requestCancellation: async () => ({ kind: 'refresh-required' }),
      loadReservation: loader.loadReservation,
      invalidateReservationLoad: loader.invalidate,
      showToast: () => undefined,
      returnOrders: () => undefined,
      openPlan: () => undefined,
    })

    expect(canCancelSubmittedBooking(reservation, plan, true)).toBe(true)
    await controller.cancelOrder()
    expect(canCancelSubmittedBooking(reservation, plan, true)).toBe(false)
    expect(plan).toMatchObject({ status: 'CONFIRMED', serviceOrderId: 'order-1' })
  })

  it('keeps the order unchanged when cancellation reports a failure', async () => {
    const { createSubmittedBookingController: factory } = await loadSubmittedBookingControllerFactories()
    const reservation: SubmittedReservation = {
      status: 'PLAN_PUBLISHED', currentPlanId: 'plan-1', marker: 'initial',
    }
    let current = reservation
    let cancelling = false
    const toasts: string[] = []
    const controller = factory({
      getReservation: () => current,
      setReservation: (value) => { current = value },
      isCancelling: () => cancelling,
      setCancelling: (value) => { cancelling = value },
      requestCancellation: async () => ({ kind: 'failed', message: '取消请求冲突，请刷新订单状态后重试' }),
      loadReservation: async () => true,
      invalidateReservationLoad: () => undefined,
      showToast: (title) => { toasts.push(title) },
      returnOrders: () => undefined,
      openPlan: () => undefined,
    })

    await controller.cancelOrder()

    expect(current).toBe(reservation)
    expect(toasts).toEqual(['取消请求冲突，请刷新订单状态后重试'])
  })

  it('returns to orders instead of opening a plan for a cancelled order', async () => {
    const { createSubmittedBookingController: factory } = await loadSubmittedBookingControllerFactories()
    let returns = 0
    let openedPlans = 0
    const controller = factory({
      getReservation: () => ({ status: 'CANCELLED', currentPlanId: 'plan-1', marker: 'server' }),
      setReservation: () => undefined,
      isCancelling: () => false,
      setCancelling: () => undefined,
      requestCancellation: async () => ({ kind: 'dismissed' }),
      loadReservation: async () => true,
      invalidateReservationLoad: () => undefined,
      showToast: () => undefined,
      returnOrders: () => { returns += 1 },
      openPlan: () => { openedPlans += 1 },
    })

    controller.handlePrimaryAction()

    expect(returns).toBe(1)
    expect(openedPlans).toBe(0)
  })

  it('reuses the load path when the submitted page becomes visible again', async () => {
    const { createSubmittedBookingController: factory } = await loadSubmittedBookingControllerFactories()
    let reloads = 0
    const controller = factory({
      getReservation: () => null,
      setReservation: () => undefined,
      isCancelling: () => false,
      setCancelling: () => undefined,
      requestCancellation: async () => ({ kind: 'dismissed' }),
      loadReservation: async () => { reloads += 1; return true },
      invalidateReservationLoad: () => undefined,
      showToast: () => undefined,
      returnOrders: () => undefined,
      openPlan: () => undefined,
    })

    await controller.reloadOnShow()

    expect(reloads).toBe(1)
  })
})
