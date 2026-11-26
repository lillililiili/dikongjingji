import { resolveBusinessMediaUrl } from '../../../shared/http/business-media'
import {
  RENTAL_INQUIRY_STATUSES,
  RENTAL_ORDER_STATUSES,
  type RentalConfirmation,
  type RentalContract,
  type RentalFee,
  type RentalInquiry,
  type RentalInquiryInput,
  type RentalInquiryPage,
  type RentalInquiryStatus,
  type RentalLessee,
  type RentalMerchant,
  type RentalOfferView,
  type RentalOrder,
  type RentalOrderPage,
  type RentalOrderStatus,
  type RentalPlace,
  type RentalQuote,
  type RentalRecord,
  type RentalReferenceUnit,
} from '../domain/rental'

type RentalRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST'
  data?: unknown
  headers?: Record<string, string>
}) => Promise<T>

// The server sends every field; a missing one reads the same as null.
type Loose<T> = { [K in keyof T]?: T[K] | null }

type RentalInquiryDto = Loose<Omit<RentalInquiry, 'quote' | 'place' | 'lessee' | 'item' | 'merchant' | 'order'>> & {
  inquiryId: string
  status: RentalInquiryStatus
  version: number
  lessee?: Loose<RentalLessee> | null
  merchant?: Loose<RentalInquiry['merchant']> | null
  item?: Loose<RentalInquiry['item']> | null
  place?: Loose<RentalPlace> | null
  quote?: (Loose<RentalQuote> & { fees?: Loose<RentalFee>[] | null }) | null
  order?: Loose<NonNullable<RentalInquiry['order']>> | null
}

type RentalOrderDto = Loose<Omit<RentalOrder, 'contract' | 'payment' | 'handoverRecord' | 'returnRecord' | 'refund'>> & {
  rentalOrderId: string
  status: RentalOrderStatus
  version: number
  contract?: (Loose<RentalContract> & {
    fees?: Loose<RentalFee>[] | null
    lessee?: Loose<RentalLessee> | null
    lessor?: Loose<RentalContract['lessor']> | null
    place?: Loose<RentalPlace> | null
  }) | null
  payment?: Loose<RentalOrder['payment']> | null
  handoverRecord?: Loose<RentalRecord> | null
  returnRecord?: Loose<RentalRecord> | null
  refund?: Loose<NonNullable<RentalOrder['refund']>> | null
}

interface PageDto<T> {
  total: number
  hasMore: boolean
  items: T[]
}

interface MallProductDto {
  productId: string
  name: string
  saleMode: string
  coverImage?: { url: string } | null
  offers: Array<{
    offerId: string
    skuName?: string | null
    merchant?: { name?: string | null } | null
    amountMinor?: number | null
    unit?: string | null
    fulfilledFrom?: string | null
    onlinePayment?: boolean | null
  }>
}

interface CurrentAccountDto {
  displayName?: string | null
  organizationName?: string | null
  organizationType?: string | null
}

export interface RentalApi {
  // The rental offer of a mall product in a city; null when the product is not a rental product or
  // the offer is not sold there any more.
  readOffer(productId: string, offerId: string, cityCode: string): Promise<RentalOfferView | null>
  // Who an inquiry is recorded under, as the server will decide it (the account's customer organization).
  readLessee(accessToken: string): Promise<RentalLessee>
  submitInquiry(accessToken: string, idempotencyKey: string, input: RentalInquiryInput): Promise<RentalInquiry>
  listInquiries(accessToken: string, status: RentalInquiryStatus | null, page: number, size: number): Promise<RentalInquiryPage>
  readInquiry(accessToken: string, inquiryId: string): Promise<RentalInquiry>
  closeInquiry(accessToken: string, inquiryId: string): Promise<RentalInquiry>
  // `inquiryVersion` is the version the page read; it goes out as If-Match.
  confirmContract(accessToken: string, inquiryId: string, inquiryVersion: number, quoteVersion: number): Promise<RentalConfirmation>
  listOrders(accessToken: string, status: RentalOrderStatus | null, page: number, size: number): Promise<RentalOrderPage>
  readOrder(accessToken: string, rentalOrderId: string): Promise<RentalOrder>
  confirmHandover(accessToken: string, rentalOrderId: string): Promise<RentalOrder>
  confirmReturn(accessToken: string, rentalOrderId: string): Promise<RentalOrder>
}

// Every call carries the access token it was given; a rejected token reaches the page as an
// error (status 401) and the page decides where the user goes.
export function createRentalApi(request: RentalRequest): RentalApi {
  const auth = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })
  const inquiryUrl = (inquiryId: string) => `/api/v1/rental-inquiries/${encodeURIComponent(inquiryId)}`
  const orderUrl = (orderId: string) => `/api/v1/rental-orders/${encodeURIComponent(orderId)}`

  return {
    async readOffer(productId, offerId, cityCode) {
      const dto = await request<MallProductDto>({
        url: `/api/v1/mall/products/${encodeURIComponent(productId)}?cityCode=${encodeURIComponent(cityCode)}`,
        method: 'GET',
      })
      return toOfferView(dto, offerId)
    },
    async readLessee(accessToken) {
      const dto = await request<CurrentAccountDto>({ url: '/api/v1/auth/me', method: 'GET', headers: auth(accessToken) })
      const organizationName = dto.organizationName?.trim() || null
      return dto.organizationType === 'CUSTOMER' && organizationName
        ? { kind: 'ORGANIZATION', organizationName, contactName: dto.displayName ?? '' }
        : { kind: 'PERSONAL', organizationName: null, contactName: dto.displayName ?? '' }
    },
    async submitInquiry(accessToken, idempotencyKey, input) {
      return toRentalInquiry(await request<RentalInquiryDto>({
        url: '/api/v1/rental-inquiries',
        method: 'POST',
        data: toInquiryBody(input),
        headers: { ...auth(accessToken), 'Idempotency-Key': idempotencyKey },
      }))
    },
    async listInquiries(accessToken, status, page, size) {
      const filter = status ? `status=${status}&` : ''
      const dto = await request<PageDto<RentalInquiryDto>>({
        url: `/api/v1/rental-inquiries?${filter}page=${page}&size=${size}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return { total: dto.total, hasMore: Boolean(dto.hasMore), items: dto.items.map(toRentalInquiry) }
    },
    async readInquiry(accessToken, inquiryId) {
      return toRentalInquiry(await request<RentalInquiryDto>({ url: inquiryUrl(inquiryId), method: 'GET', headers: auth(accessToken) }))
    },
    async closeInquiry(accessToken, inquiryId) {
      return toRentalInquiry(await request<RentalInquiryDto>({
        url: `${inquiryUrl(inquiryId)}/close`,
        method: 'POST',
        headers: auth(accessToken),
      }))
    },
    async confirmContract(accessToken, inquiryId, inquiryVersion, quoteVersion) {
      const dto = await request<RentalConfirmation>({
        url: `${inquiryUrl(inquiryId)}/confirm`,
        method: 'POST',
        data: { quoteVersion, agreed: true },
        // The ETag of an inquiry is its version in quotes.
        headers: { ...auth(accessToken), 'If-Match': `"${inquiryVersion}"` },
      })
      return {
        inquiryId: dto.inquiryId,
        rentalOrderId: dto.rentalOrderId,
        tradeOrderId: dto.tradeOrderId,
        payBefore: dto.payBefore,
      }
    },
    async listOrders(accessToken, status, page, size) {
      const filter = status ? `status=${status}&` : ''
      const dto = await request<PageDto<RentalOrderDto>>({
        url: `/api/v1/rental-orders?${filter}page=${page}&size=${size}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return { total: dto.total, hasMore: Boolean(dto.hasMore), items: dto.items.map(toRentalOrder) }
    },
    async readOrder(accessToken, rentalOrderId) {
      return toRentalOrder(await request<RentalOrderDto>({ url: orderUrl(rentalOrderId), method: 'GET', headers: auth(accessToken) }))
    },
    async confirmHandover(accessToken, rentalOrderId) {
      return toRentalOrder(await request<RentalOrderDto>({
        url: `${orderUrl(rentalOrderId)}/handover-confirmation`,
        method: 'POST',
        headers: auth(accessToken),
      }))
    },
    async confirmReturn(accessToken, rentalOrderId) {
      return toRentalOrder(await request<RentalOrderDto>({
        url: `${orderUrl(rentalOrderId)}/return-confirmation`,
        method: 'POST',
        headers: auth(accessToken),
      }))
    },
  }
}

// An empty note goes out as null.
function toInquiryBody(input: RentalInquiryInput) {
  const note = input.note.trim()
  return {
    offerId: input.offerId,
    quantity: input.quantity,
    startDate: input.startDate,
    days: input.days,
    handoverMode: input.handoverMode,
    returnMode: input.returnMode,
    addressId: input.addressId,
    note: note ? note : null,
  }
}

function toUnit(unit: string | null | undefined): RentalReferenceUnit {
  return unit === 'HALF_DAY' || unit === 'ITEM' ? unit : 'DAY'
}

export function toOfferView(dto: MallProductDto, offerId: string): RentalOfferView | null {
  if (dto.saleMode !== 'RENT') return null
  const offer = dto.offers.find((item) => item.offerId === offerId)
  if (!offer) return null
  return {
    offerId: offer.offerId,
    productId: dto.productId,
    productName: dto.name,
    skuName: offer.skuName ?? '',
    coverUrl: resolveBusinessMediaUrl(dto.coverImage?.url),
    merchantName: offer.merchant?.name ?? '',
    amountMinor: offer.amountMinor ?? null,
    unit: toUnit(offer.unit),
    fulfilledFrom: offer.fulfilledFrom ?? null,
    onlinePayment: offer.onlinePayment === true,
  }
}

function toLessee(dto: Loose<RentalLessee> | null | undefined): RentalLessee {
  return {
    kind: dto?.kind === 'ORGANIZATION' ? 'ORGANIZATION' : 'PERSONAL',
    organizationName: dto?.organizationName ?? null,
    contactName: dto?.contactName ?? '',
  }
}

function toPlace(dto: Loose<RentalPlace> | null | undefined): RentalPlace {
  return {
    addressId: dto?.addressId ?? null,
    recipientName: dto?.recipientName ?? '',
    phone: dto?.phone ?? '',
    provinceName: dto?.provinceName ?? '',
    cityCode: dto?.cityCode ?? '',
    cityName: dto?.cityName ?? '',
    detail: dto?.detail ?? '',
  }
}

function toFees(fees: Loose<RentalFee>[] | null | undefined): RentalFee[] {
  return (fees ?? []).map((fee) => ({ name: fee.name ?? '', note: fee.note ?? null, amountMinor: fee.amountMinor ?? 0 }))
}

function toRecord(dto: Loose<RentalRecord> | null | undefined): RentalRecord | null {
  if (!dto?.recordedAt) return null
  return {
    occurredAt: dto.occurredAt ?? dto.recordedAt,
    note: dto.note ?? '',
    recordedAt: dto.recordedAt,
    confirmedAt: dto.confirmedAt ?? null,
  }
}

function inquiryStatus(value: string): RentalInquiryStatus {
  return (RENTAL_INQUIRY_STATUSES as readonly string[]).includes(value) ? value as RentalInquiryStatus : 'CLOSED'
}

function orderStatus(value: string | null | undefined): RentalOrderStatus {
  return value && (RENTAL_ORDER_STATUSES as readonly string[]).includes(value) ? value as RentalOrderStatus : 'CLOSED'
}

// Only an explicit true from the server shows 资质已审核; a missing field never reads as reviewed.
function toRentalMerchant(dto: Loose<RentalMerchant> | null | undefined): RentalMerchant {
  return {
    merchantId: dto?.merchantId ?? '',
    name: dto?.name ?? '',
    qualificationReviewed: dto?.qualificationReviewed === true,
  }
}

export function toRentalInquiry(dto: RentalInquiryDto): RentalInquiry {
  const quote = dto.quote
  return {
    inquiryId: dto.inquiryId,
    status: inquiryStatus(dto.status),
    closeReason: dto.closeReason === 'CUSTOMER' || dto.closeReason === 'NO_QUOTE' ? dto.closeReason : null,
    declineReason: dto.declineReason ?? null,
    lessee: toLessee(dto.lessee),
    merchant: toRentalMerchant(dto.merchant),
    item: {
      offerId: dto.item?.offerId ?? '',
      productId: dto.item?.productId ?? '',
      productName: dto.item?.productName ?? '',
      skuName: dto.item?.skuName ?? '',
      coverUrl: resolveBusinessMediaUrl(dto.item?.coverUrl),
      referenceAmountMinor: dto.item?.referenceAmountMinor ?? null,
      referenceUnit: toUnit(dto.item?.referenceUnit),
    },
    quantity: dto.quantity ?? 1,
    startDate: dto.startDate ?? '',
    endDate: dto.endDate ?? '',
    days: dto.days ?? 1,
    handoverMode: dto.handoverMode === 'PICKUP' ? 'PICKUP' : 'DELIVERY',
    returnMode: dto.returnMode === 'DROP_OFF' ? 'DROP_OFF' : 'COLLECT',
    place: toPlace(dto.place),
    note: dto.note ?? null,
    submittedAt: dto.submittedAt ?? '',
    quoteDeadline: dto.quoteDeadline ?? '',
    quote: quote
      ? {
          version: quote.version ?? 1,
          dailyRentMinor: quote.dailyRentMinor ?? 0,
          days: quote.days ?? 1,
          quantity: quote.quantity ?? 1,
          rentMinor: quote.rentMinor ?? 0,
          fees: toFees(quote.fees),
          totalMinor: quote.totalMinor ?? 0,
          scheduleAvailable: Boolean(quote.scheduleAvailable),
          validUntil: quote.validUntil ?? '',
          expired: Boolean(quote.expired),
          terms: quote.terms ?? '',
          quotedAt: quote.quotedAt ?? '',
        }
      : null,
    confirmable: Boolean(dto.confirmable),
    closable: Boolean(dto.closable),
    order: dto.order?.rentalOrderId
      ? {
          rentalOrderId: dto.order.rentalOrderId,
          status: orderStatus(dto.order.status),
          tradeOrderId: dto.order.tradeOrderId ?? '',
        }
      : null,
    updatedAt: dto.updatedAt ?? dto.submittedAt ?? '',
    version: dto.version,
  }
}

export function toRentalOrder(dto: RentalOrderDto): RentalOrder {
  const contract = dto.contract
  return {
    rentalOrderId: dto.rentalOrderId,
    inquiryId: dto.inquiryId ?? '',
    status: orderStatus(dto.status),
    contract: {
      contractNo: contract?.contractNo ?? '',
      confirmedAt: contract?.confirmedAt ?? '',
      quoteVersion: contract?.quoteVersion ?? 1,
      lessee: toLessee(contract?.lessee),
      lessor: toRentalMerchant(contract?.lessor),
      productName: contract?.productName ?? '',
      skuName: contract?.skuName ?? '',
      quantity: contract?.quantity ?? 1,
      startDate: contract?.startDate ?? '',
      endDate: contract?.endDate ?? '',
      days: contract?.days ?? 1,
      dailyRentMinor: contract?.dailyRentMinor ?? 0,
      rentMinor: contract?.rentMinor ?? 0,
      fees: toFees(contract?.fees),
      totalMinor: contract?.totalMinor ?? 0,
      handoverMode: contract?.handoverMode === 'PICKUP' ? 'PICKUP' : 'DELIVERY',
      returnMode: contract?.returnMode === 'DROP_OFF' ? 'DROP_OFF' : 'COLLECT',
      place: toPlace(contract?.place),
      terms: contract?.terms ?? '',
    },
    payment: {
      tradeOrderId: dto.payment?.tradeOrderId ?? '',
      amountMinor: dto.payment?.amountMinor ?? 0,
      payBefore: dto.payment?.payBefore ?? '',
      paidAt: dto.payment?.paidAt ?? null,
    },
    handoverRecord: toRecord(dto.handoverRecord),
    returnRecord: toRecord(dto.returnRecord),
    rentStartedAt: dto.rentStartedAt ?? null,
    endedAt: dto.endedAt ?? null,
    closedAt: dto.closedAt ?? null,
    cancelledAt: dto.cancelledAt ?? null,
    cancelReason: dto.cancelReason ?? null,
    refund: dto.refund?.refundId
      ? {
          refundId: dto.refund.refundId,
          amountMinor: dto.refund.amountMinor ?? 0,
          status: dto.refund.status ?? 'REQUESTED',
        }
      : null,
    handoverConfirmable: Boolean(dto.handoverConfirmable),
    returnConfirmable: Boolean(dto.returnConfirmable),
    createdAt: dto.createdAt ?? '',
    updatedAt: dto.updatedAt ?? '',
    version: dto.version,
  }
}
