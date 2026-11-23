import {
  AFTERSALE_ACTIONS,
  AFTERSALE_IMAGE_SLOTS,
  AFTERSALE_KINDS,
  AFTERSALE_REASONS,
  AFTERSALE_STATUSES,
  AFTERSALE_UNAVAILABLE_REASONS,
  type Aftersale,
  type AftersaleAction,
  type AftersaleEligibility,
  type AftersaleImageSlot,
  type AftersaleImageUpload,
  type AftersaleKind,
  type AftersaleMerchant,
  type AftersalePage,
  type AftersaleReason,
  type AftersaleRefundStatus,
  type AftersaleStatus,
  type AftersaleTab,
  type AftersaleUnavailableReason,
  type ApplyAftersaleInput,
  type ReturnShipmentInput,
} from '../domain/aftersale'
import type { TradeImage, TradeOrderLine } from '../domain/trade-order'

type AftersaleRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST'
  data?: unknown
  headers?: Record<string, string>
}) => Promise<T>

/**
 * 两件 JSON 请求以外的事：凭证图片用 multipart 上传（uni.uploadFile），读图要带登录令牌，
 * 读回的二进制要变成 <image> 能显示的地址。uni 的部分在 aftersale-api-client.ts。
 */
export interface AftersaleBinaryPort {
  upload(input: { url: string; filePath: string; headers: Record<string, string> }): Promise<unknown>
  readImage(input: { url: string; headers: Record<string, string> }): Promise<string>
}

export interface AftersaleApi {
  eligibility(accessToken: string, orderId: string): Promise<AftersaleEligibility>
  apply(accessToken: string, orderId: string, input: ApplyAftersaleInput, idempotencyKey: string): Promise<Aftersale>
  list(accessToken: string, tab: AftersaleTab | 'ALL', page: number, size: number): Promise<AftersalePage>
  read(accessToken: string, aftersaleId: string): Promise<Aftersale>
  revoke(accessToken: string, aftersaleId: string, version: number): Promise<Aftersale>
  submitReturn(accessToken: string, aftersaleId: string, version: number, input: ReturnShipmentInput): Promise<Aftersale>
  uploadImage(accessToken: string, slot: AftersaleImageSlot, filePath: string): Promise<AftersaleImageUpload>
  // 一张自己上传的凭证图片，变成能放进 <image src> 的地址。
  imageSource(accessToken: string, imageId: string): Promise<string>
}

// The DTOs as they arrive; nullable fields may also be missing on a fixture.
interface LineDto {
  lineId: string
  productId: string
  offerId: string
  productName: string
  skuName: string
  coverImage?: TradeImage | null
  unitAmountMinor: number
  quantity: number
  subtotalMinor: number
}

interface MerchantDto {
  organizationId: string
  name: string
  aftersaleParty?: string | null
}

interface AftersaleDto {
  aftersaleId: string
  aftersaleNo: string
  orderId: string
  orderNo: string
  kind: string
  reason: string
  description?: string | null
  status: string
  merchant: MerchantDto
  lines: LineDto[]
  paidMinor: number
  requestedMinor: number
  refundMinor?: number | null
  returnAddress?: { contactName?: string | null; phone?: string | null; address: string; note?: string | null } | null
  returnWaybill?: { carrierName: string; trackingNo: string; shippedAt?: string | null } | null
  rejectReason?: string | null
  merchantNote?: string | null
  refund?: { status: string; amountMinor: number; succeededAt?: string | null; channelTransactionId?: string | null } | null
  images?: Array<{ imageId: string; slot: string }>
  dueAt?: string | null
  submittedAt: string
  reviewedAt?: string | null
  returnShippedAt?: string | null
  receivedAt?: string | null
  refundedAt?: string | null
  closedAt?: string | null
  timeline?: Array<{ action: string; status: string; occurredAt: string }>
  actions?: Partial<Record<'canRevoke' | 'canSubmitReturn' | 'canEditReturn' | 'canReapply', boolean>>
  version: number
}

interface AftersalePageDto {
  items: AftersaleDto[]
  hasMore: boolean
  total: number
  counts?: { doing?: number; done?: number; closed?: number } | null
}

interface EligibilityDto {
  orderId: string
  orderNo: string
  orderStatus: string
  lines: LineDto[]
  paidMinor: number
  maxRefundMinor: number
  merchant: MerchantDto
  kinds: Array<{ kind: string; available: boolean; unavailableReason?: string | null }>
  reasons: Array<{ reason: string; kind: string; available: boolean; unavailableReason?: string | null }>
  openAftersaleId?: string | null
  shippedAt?: string | null
  completedAt?: string | null
}

const REFUND_STATUSES: readonly AftersaleRefundStatus[] = ['REQUESTED', 'PROCESSING', 'SUCCEEDED', 'CLOSED', 'ABNORMAL']

function oneOf<T extends string>(values: readonly T[], value: unknown): T | null {
  return typeof value === 'string' && (values as readonly string[]).includes(value) ? value as T : null
}

// A status the App does not know cannot be drawn truthfully; the page says it could not load.
function required<T extends string>(values: readonly T[], value: unknown, field: string): T {
  const known = oneOf(values, value)
  if (known === null) throw new Error(`AFTERSALE_INVALID_${field}`)
  return known
}

const text = (value: string | null | undefined): string | null => {
  const trimmed = value?.trim() ?? ''
  return trimmed === '' ? null : trimmed
}

function toLine(line: LineDto): TradeOrderLine {
  return {
    lineId: line.lineId,
    productId: line.productId,
    offerId: line.offerId,
    productName: line.productName,
    skuName: line.skuName,
    // 只认 HTTPS 图片，和订单的其他图片一样。
    coverImage: line.coverImage && /^https:\/\//i.test(line.coverImage.url) ? { url: line.coverImage.url, alt: line.coverImage.alt } : null,
    unitAmountMinor: line.unitAmountMinor,
    quantity: line.quantity,
    subtotalMinor: line.subtotalMinor,
  }
}

function toMerchant(merchant: MerchantDto): AftersaleMerchant {
  return { organizationId: merchant.organizationId, name: merchant.name, aftersaleParty: text(merchant.aftersaleParty) }
}

function toUnavailable(value: unknown): AftersaleUnavailableReason | null {
  return oneOf(AFTERSALE_UNAVAILABLE_REASONS, value)
}

export function toAftersale(dto: AftersaleDto): Aftersale {
  return {
    aftersaleId: dto.aftersaleId,
    aftersaleNo: dto.aftersaleNo,
    orderId: dto.orderId,
    orderNo: dto.orderNo,
    kind: required(AFTERSALE_KINDS, dto.kind, 'KIND'),
    reason: required(AFTERSALE_REASONS, dto.reason, 'REASON'),
    description: text(dto.description),
    status: required(AFTERSALE_STATUSES, dto.status, 'STATUS'),
    merchant: toMerchant(dto.merchant),
    lines: dto.lines.map(toLine),
    paidMinor: dto.paidMinor,
    requestedMinor: dto.requestedMinor,
    refundMinor: dto.refundMinor ?? null,
    returnAddress: dto.returnAddress && text(dto.returnAddress.address)
      ? {
        contactName: text(dto.returnAddress.contactName),
        phone: text(dto.returnAddress.phone),
        address: dto.returnAddress.address.trim(),
        note: text(dto.returnAddress.note),
      }
      : null,
    returnWaybill: dto.returnWaybill
      ? {
        carrierName: dto.returnWaybill.carrierName,
        trackingNo: dto.returnWaybill.trackingNo,
        shippedAt: dto.returnWaybill.shippedAt ?? null,
      }
      : null,
    rejectReason: text(dto.rejectReason),
    merchantNote: text(dto.merchantNote),
    refund: dto.refund && oneOf(REFUND_STATUSES, dto.refund.status)
      ? {
        status: dto.refund.status as AftersaleRefundStatus,
        amountMinor: dto.refund.amountMinor,
        succeededAt: dto.refund.succeededAt ?? null,
        channelTransactionId: text(dto.refund.channelTransactionId),
      }
      : null,
    // 认不出的格子和进度项丢掉，不猜。
    images: (dto.images ?? []).flatMap((image) => {
      const slot = oneOf(AFTERSALE_IMAGE_SLOTS, image.slot)
      return slot ? [{ imageId: image.imageId, slot }] : []
    }),
    dueAt: dto.dueAt ?? null,
    submittedAt: dto.submittedAt,
    reviewedAt: dto.reviewedAt ?? null,
    returnShippedAt: dto.returnShippedAt ?? null,
    receivedAt: dto.receivedAt ?? null,
    refundedAt: dto.refundedAt ?? null,
    closedAt: dto.closedAt ?? null,
    timeline: (dto.timeline ?? []).flatMap((event) => {
      const action = oneOf<AftersaleAction>(AFTERSALE_ACTIONS, event.action)
      const status = oneOf<AftersaleStatus>(AFTERSALE_STATUSES, event.status)
      return action && status ? [{ action, status, occurredAt: event.occurredAt }] : []
    }),
    // Only an explicit true shows a button.
    actions: {
      canRevoke: dto.actions?.canRevoke === true,
      canSubmitReturn: dto.actions?.canSubmitReturn === true,
      canEditReturn: dto.actions?.canEditReturn === true,
      canReapply: dto.actions?.canReapply === true,
    },
    version: dto.version,
  }
}

export function toAftersaleEligibility(dto: EligibilityDto): AftersaleEligibility {
  return {
    orderId: dto.orderId,
    orderNo: dto.orderNo,
    orderStatus: dto.orderStatus,
    lines: dto.lines.map(toLine),
    paidMinor: dto.paidMinor,
    maxRefundMinor: dto.maxRefundMinor,
    merchant: toMerchant(dto.merchant),
    kinds: dto.kinds.flatMap((option) => {
      const kind = oneOf<AftersaleKind>(AFTERSALE_KINDS, option.kind)
      return kind
        ? [{ kind, available: option.available === true, unavailableReason: toUnavailable(option.unavailableReason) }]
        : []
    }),
    reasons: dto.reasons.flatMap((option) => {
      const reason = oneOf<AftersaleReason>(AFTERSALE_REASONS, option.reason)
      const kind = oneOf<AftersaleKind>(AFTERSALE_KINDS, option.kind)
      return reason && kind
        ? [{ reason, kind, available: option.available === true, unavailableReason: toUnavailable(option.unavailableReason) }]
        : []
    }),
    openAftersaleId: dto.openAftersaleId ?? null,
    shippedAt: dto.shippedAt ?? null,
    completedAt: dto.completedAt ?? null,
  }
}

export function createAftersaleApi(request: AftersaleRequest, binary: AftersaleBinaryPort): AftersaleApi {
  const auth = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })
  const aftersaleUrl = (aftersaleId: string) => `/api/v1/trade/aftersales/${encodeURIComponent(aftersaleId)}`
  // 售后单的 ETag 是带引号的版本号。
  const ifMatch = (accessToken: string, version: number) => ({ ...auth(accessToken), 'If-Match': `"${version}"` })
  return {
    async eligibility(accessToken, orderId) {
      return toAftersaleEligibility(await request<EligibilityDto>({
        url: `/api/v1/trade/orders/${encodeURIComponent(orderId)}/aftersale-eligibility`,
        method: 'GET',
        headers: auth(accessToken),
      }))
    },
    async apply(accessToken, orderId, input, idempotencyKey) {
      const body: Record<string, unknown> = { kind: input.kind, reason: input.reason }
      const description = input.description.trim()
      if (description) body.description = description
      if (input.requestedMinor !== null) body.requestedMinor = input.requestedMinor
      if (input.imageIds.length > 0) body.imageIds = input.imageIds
      return toAftersale(await request<AftersaleDto>({
        url: `/api/v1/trade/orders/${encodeURIComponent(orderId)}/aftersales`,
        method: 'POST',
        data: body,
        headers: { ...auth(accessToken), 'Idempotency-Key': idempotencyKey },
      }))
    },
    async list(accessToken, tab, page, size) {
      const dto = await request<AftersalePageDto>({
        url: `/api/v1/trade/aftersales?tab=${tab}&page=${page}&size=${size}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return {
        items: dto.items.map(toAftersale),
        hasMore: Boolean(dto.hasMore),
        total: dto.total,
        counts: {
          doing: dto.counts?.doing ?? 0,
          done: dto.counts?.done ?? 0,
          closed: dto.counts?.closed ?? 0,
        },
      }
    },
    async read(accessToken, aftersaleId) {
      return toAftersale(await request<AftersaleDto>({ url: aftersaleUrl(aftersaleId), method: 'GET', headers: auth(accessToken) }))
    },
    async revoke(accessToken, aftersaleId, version) {
      return toAftersale(await request<AftersaleDto>({
        url: `${aftersaleUrl(aftersaleId)}/revoke`,
        method: 'POST',
        headers: ifMatch(accessToken, version),
      }))
    },
    async submitReturn(accessToken, aftersaleId, version, input) {
      return toAftersale(await request<AftersaleDto>({
        url: `${aftersaleUrl(aftersaleId)}/return-shipment`,
        method: 'POST',
        data: { carrierName: input.carrierName.trim(), trackingNo: input.trackingNo.trim() },
        headers: ifMatch(accessToken, version),
      }))
    },
    async uploadImage(accessToken, slot, filePath) {
      const dto = await binary.upload({
        url: `/api/v1/trade/aftersale-images?slot=${slot}`,
        filePath,
        headers: auth(accessToken),
      }) as { imageId?: unknown; slot?: unknown }
      const uploadedSlot = oneOf(AFTERSALE_IMAGE_SLOTS, dto?.slot) ?? slot
      if (typeof dto?.imageId !== 'string') throw new Error('AFTERSALE_UPLOAD_INVALID')
      return { imageId: dto.imageId, slot: uploadedSlot }
    },
    imageSource(accessToken, imageId) {
      return binary.readImage({
        url: `/api/v1/trade/aftersale-images/${encodeURIComponent(imageId)}`,
        headers: auth(accessToken),
      })
    },
  }
}
