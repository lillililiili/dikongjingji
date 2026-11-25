import { businessImage } from '../../../shared/http/business-media'
import {
  MALL_INQUIRY_SORTS,
  MALL_INQUIRY_STATUSES,
  type MallInquiry,
  type MallInquiryBuyer,
  type MallInquiryChosen,
  type MallInquiryDetail,
  type MallInquiryInput,
  type MallInquiryInvitation,
  type MallInquiryMerchantRef,
  type MallInquiryOffer,
  type MallInquiryPage,
  type MallInquiryQuote,
  type MallInquiryQuoteMerchant,
  type MallInquiryQuoteStatus,
  type MallInquirySort,
  type MallInquiryStatus,
} from '../domain/inquiry'
import type { MallMerchantKind } from '../domain/mall'

type InquiryRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST'
  data?: unknown
  headers?: Record<string, string>
}) => Promise<T>

// The server sends every field; a missing one reads the same as null.
type Loose<T> = { [K in keyof T]?: T[K] | null }

type MerchantDto = Loose<MallInquiryMerchantRef>

type InquiryDto = Loose<Omit<MallInquiry, 'status' | 'closeReason' | 'buyer' | 'product' | 'merchants' | 'chosen'>> & {
  closeReason?: string | null
  inquiryId: string
  status: string
  version: number
  buyer?: Loose<MallInquiryBuyer> | null
  product?: (Loose<MallInquiry['product']> & { coverImage?: { url?: string | null; alt?: string | null } | null }) | null
  merchants?: Array<Loose<Omit<MallInquiryInvitation, 'merchant'>> & { merchant?: MerchantDto | null }> | null
  chosen?: (Loose<Omit<MallInquiryChosen, 'merchant'>> & { merchant?: MerchantDto | null }) | null
}

type QuoteMerchantDto = Loose<MallInquiryQuoteMerchant>

type QuoteDto = Loose<Omit<MallInquiryQuote, 'merchant'>> & { quoteId: string; merchant?: QuoteMerchantDto | null }

interface PageDto<T> {
  total: number
  hasMore: boolean
  items: T[]
}

export interface MallInquiryApi {
  // Who an inquiry is recorded under, as the server will decide it (the account's customer organization).
  readBuyer(accessToken: string): Promise<MallInquiryBuyer>
  // The merchants that sell this inquiry product in the city, for the form's merchant picker.
  listOffers(accessToken: string, productId: string, cityCode: string): Promise<MallInquiryOffer[]>
  submit(accessToken: string, idempotencyKey: string, input: MallInquiryInput): Promise<MallInquiry>
  list(accessToken: string, status: MallInquiryStatus | null, page: number, size: number): Promise<MallInquiryPage>
  read(accessToken: string, inquiryId: string, sort: MallInquirySort): Promise<MallInquiryDetail>
  // `inquiryVersion` is the version the page read; it goes out as If-Match.
  choose(accessToken: string, inquiryId: string, inquiryVersion: number, quoteId: string, quoteVersion: number): Promise<MallInquiry>
  close(accessToken: string, inquiryId: string): Promise<MallInquiry>
}

// Every call carries the access token it was given; a rejected token reaches the page as an error
// (status 401) and the page decides where the user goes.
export function createMallInquiryApi(request: InquiryRequest): MallInquiryApi {
  const auth = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })
  const inquiryUrl = (inquiryId: string) => `/api/v1/mall/inquiries/${encodeURIComponent(inquiryId)}`

  return {
    async readBuyer(accessToken) {
      const dto = await request<{ displayName?: string | null; organizationName?: string | null; organizationType?: string | null }>({
        url: '/api/v1/auth/me',
        method: 'GET',
        headers: auth(accessToken),
      })
      const organizationName = dto.organizationName?.trim() || null
      return dto.organizationType === 'CUSTOMER' && organizationName
        ? { kind: 'ORGANIZATION', organizationName, contactName: dto.displayName ?? '' }
        : { kind: 'PERSONAL', organizationName: null, contactName: dto.displayName ?? '' }
    },
    async listOffers(accessToken, productId, cityCode) {
      const dto = await request<{ items?: Array<Loose<Omit<MallInquiryOffer, 'merchant'>> & { offerId: string; merchant?: MerchantDto | null }> }>({
        url: `/api/v1/mall/inquiry-offers?productId=${encodeURIComponent(productId)}&cityCode=${encodeURIComponent(cityCode)}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return (dto.items ?? []).map((item) => ({
        offerId: item.offerId,
        merchant: toMerchant(item.merchant),
        deliveryNote: item.deliveryNote ?? null,
        canInquire: Boolean(item.canInquire),
      }))
    },
    async submit(accessToken, idempotencyKey, input) {
      return toMallInquiry(await request<InquiryDto>({
        url: '/api/v1/mall/inquiries',
        method: 'POST',
        data: toInquiryBody(input),
        headers: { ...auth(accessToken), 'Idempotency-Key': idempotencyKey },
      }))
    },
    async list(accessToken, status, page, size) {
      const filter = status ? `status=${status}&` : ''
      const dto = await request<PageDto<InquiryDto>>({
        url: `/api/v1/mall/inquiries?${filter}page=${page}&size=${size}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return { total: dto.total, hasMore: Boolean(dto.hasMore), items: dto.items.map(toMallInquiry) }
    },
    async read(accessToken, inquiryId, sort) {
      const dto = await request<{ inquiry: InquiryDto; sort?: string | null; quotes?: QuoteDto[] | null }>({
        url: `${inquiryUrl(inquiryId)}?sort=${sort}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return {
        inquiry: toMallInquiry(dto.inquiry),
        sort: toSort(dto.sort, sort),
        quotes: (dto.quotes ?? []).map(toMallInquiryQuote),
      }
    },
    async choose(accessToken, inquiryId, inquiryVersion, quoteId, quoteVersion) {
      return toMallInquiry(await request<InquiryDto>({
        url: `${inquiryUrl(inquiryId)}/choice`,
        method: 'POST',
        data: { quoteId, quoteVersion },
        // The ETag of an inquiry is its version in quotes.
        headers: { ...auth(accessToken), 'If-Match': `"${inquiryVersion}"` },
      }))
    },
    async close(accessToken, inquiryId) {
      return toMallInquiry(await request<InquiryDto>({
        url: `${inquiryUrl(inquiryId)}/close`,
        method: 'POST',
        headers: auth(accessToken),
      }))
    },
  }
}

// The requirement keeps its line breaks; an empty expected date goes out as null.
function toInquiryBody(input: MallInquiryInput) {
  return {
    productId: input.productId,
    requirement: input.requirement.trim(),
    quantity: input.quantity,
    cityCode: input.cityCode,
    expectedDate: input.expectedDate || null,
    offerIds: [...input.offerIds],
  }
}

const MERCHANT_KINDS: readonly MallMerchantKind[] = ['FACTORY_DIRECT', 'AUTHORIZED_DEALER', 'LOCAL_STORE']
const QUOTE_STATUSES: readonly MallInquiryQuoteStatus[] = ['ACTIVE', 'EXPIRED', 'WITHDRAWN', 'CHOSEN', 'LAPSED']

function toMerchant(dto: MerchantDto | null | undefined): MallInquiryMerchantRef {
  const kind = dto?.kind
  return {
    organizationId: dto?.organizationId ?? '',
    name: dto?.name ?? '',
    kind: kind && MERCHANT_KINDS.includes(kind) ? kind : 'LOCAL_STORE',
  }
}

function toSort(value: string | null | undefined, fallback: MallInquirySort): MallInquirySort {
  return value && (MALL_INQUIRY_SORTS as readonly string[]).includes(value) ? value as MallInquirySort : fallback
}

// An unknown status reads as closed: the page then offers nothing it cannot do.
function toStatus(value: string): MallInquiryStatus {
  return (MALL_INQUIRY_STATUSES as readonly string[]).includes(value) ? value as MallInquiryStatus : 'CLOSED'
}

export function toMallInquiry(dto: InquiryDto): MallInquiry {
  const chosen = dto.chosen
  const closeReason = dto.closeReason
  return {
    inquiryId: dto.inquiryId,
    status: toStatus(dto.status),
    closeReason: closeReason === 'CUSTOMER' || closeReason === 'NO_QUOTES' || closeReason === 'QUOTES_LAPSED' ? closeReason : null,
    buyer: {
      kind: dto.buyer?.kind === 'ORGANIZATION' && dto.buyer.organizationName ? 'ORGANIZATION' : 'PERSONAL',
      organizationName: dto.buyer?.organizationName ?? null,
      contactName: dto.buyer?.contactName ?? '',
    },
    product: {
      productId: dto.product?.productId ?? '',
      name: dto.product?.name ?? '',
      coverImage: businessImage(dto.product?.coverImage),
    },
    requirement: dto.requirement ?? '',
    quantity: dto.quantity ?? 1,
    cityCode: dto.cityCode ?? '',
    expectedDate: dto.expectedDate ?? null,
    merchants: (dto.merchants ?? []).map((item) => ({
      offerId: item.offerId ?? '',
      merchant: toMerchant(item.merchant),
      status: item.status === 'QUOTED' || item.status === 'DECLINED' ? item.status : 'INVITED',
      declineReason: item.declineReason ?? null,
    })),
    submittedAt: dto.submittedAt ?? '',
    quoteDeadline: dto.quoteDeadline ?? '',
    quotingOpen: Boolean(dto.quotingOpen),
    quoteCount: dto.quoteCount ?? 0,
    chosen: chosen?.quoteId
      ? {
          quoteId: chosen.quoteId,
          quoteVersion: chosen.quoteVersion ?? 1,
          offerId: chosen.offerId ?? '',
          merchant: toMerchant(chosen.merchant),
          unitAmountMinor: chosen.unitAmountMinor ?? 0,
          quantity: chosen.quantity ?? dto.quantity ?? 1,
          totalMinor: chosen.totalMinor ?? 0,
          validUntil: chosen.validUntil ?? '',
          chosenAt: chosen.chosenAt ?? '',
          orderId: chosen.orderId ?? null,
        }
      : null,
    closable: Boolean(dto.closable),
    updatedAt: dto.updatedAt ?? dto.submittedAt ?? '',
    version: dto.version,
  }
}

export function toMallInquiryQuote(dto: QuoteDto): MallInquiryQuote {
  const status = dto.status
  return {
    quoteId: dto.quoteId,
    version: dto.version ?? 1,
    offerId: dto.offerId ?? '',
    // Only an explicit true shows the tag: a missing field never reads as reviewed.
    merchant: { ...toMerchant(dto.merchant), qualificationReviewed: dto.merchant?.qualificationReviewed === true },
    status: status && QUOTE_STATUSES.includes(status) ? status : 'LAPSED',
    unitAmountMinor: dto.unitAmountMinor ?? 0,
    quantity: dto.quantity ?? 1,
    totalMinor: dto.totalMinor ?? 0,
    model: dto.model ?? '',
    leadDays: dto.leadDays ?? 0,
    includes: dto.includes?.trim() ? dto.includes : null,
    validUntil: dto.validUntil ?? '',
    quotedAt: dto.quotedAt ?? '',
    choosable: Boolean(dto.choosable),
  }
}
