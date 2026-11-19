import type { MallImage, MallMerchantKind } from './mall'

// Mirrors contracts/openapi-mall-inquiry.yaml (1.0.0, step F4): inquiry goods are asked about on an
// inquiry form, merchants quote, the customer compares and chooses one quote, then orders it as a
// normal mall order. Amounts are in fen, instants are ISO strings, dates are Beijing days.

export type MallInquiryStatus = 'OPEN' | 'QUOTED' | 'CHOSEN' | 'CLOSED'

export const MALL_INQUIRY_STATUSES: readonly MallInquiryStatus[] = ['OPEN', 'QUOTED', 'CHOSEN', 'CLOSED']

// CUSTOMER: closed by the customer; NO_QUOTES: nobody quoted within 72 hours; QUOTES_LAPSED: every
// quote expired or was withdrawn after the deadline before one was chosen.
export type MallInquiryCloseReason = 'CUSTOMER' | 'NO_QUOTES' | 'QUOTES_LAPSED'

export type MallInquiryInvitationStatus = 'INVITED' | 'QUOTED' | 'DECLINED'

export type MallInquiryQuoteStatus = 'ACTIVE' | 'EXPIRED' | 'WITHDRAWN' | 'CHOSEN' | 'LAPSED'

export type MallInquirySort = 'ALL' | 'PRICE' | 'LEAD_TIME'

export const MALL_INQUIRY_SORTS: readonly MallInquirySort[] = ['ALL', 'PRICE', 'LEAD_TIME']

export interface MallInquiryMerchantRef {
  organizationId: string
  name: string
  kind: MallMerchantKind
}

export interface MallInquiryBuyer {
  kind: 'ORGANIZATION' | 'PERSONAL'
  organizationName: string | null
  // The account's display name (经办人).
  contactName: string
}

export interface MallInquiryProduct {
  productId: string
  name: string
  coverImage: MallImage | null
}

// One merchant the inquiry was sent to.
export interface MallInquiryInvitation {
  offerId: string
  merchant: MallInquiryMerchantRef
  status: MallInquiryInvitationStatus
  declineReason: string | null
}

export interface MallInquiryChosen {
  quoteId: string
  quoteVersion: number
  offerId: string
  merchant: MallInquiryMerchantRef
  unitAmountMinor: number
  quantity: number
  totalMinor: number
  validUntil: string
  chosenAt: string
  // The latest mall order made from this quote; null before the first one.
  orderId: string | null
}

export interface MallInquiry {
  inquiryId: string
  status: MallInquiryStatus
  closeReason: MallInquiryCloseReason | null
  buyer: MallInquiryBuyer
  product: MallInquiryProduct
  requirement: string
  quantity: number
  cityCode: string
  expectedDate: string | null
  merchants: MallInquiryInvitation[]
  submittedAt: string
  quoteDeadline: string
  quotingOpen: boolean
  quoteCount: number
  chosen: MallInquiryChosen | null
  closable: boolean
  updatedAt: string
  // Sent back as If-Match when choosing a quote.
  version: number
}

export interface MallInquiryPage {
  total: number
  hasMore: boolean
  items: MallInquiry[]
}

// A merchant's latest quote.
export interface MallInquiryQuote {
  quoteId: string
  version: number
  offerId: string
  merchant: MallInquiryMerchantRef
  status: MallInquiryQuoteStatus
  unitAmountMinor: number
  quantity: number
  totalMinor: number
  model: string
  leadDays: number
  includes: string | null
  validUntil: string
  quotedAt: string
  choosable: boolean
}

export interface MallInquiryDetail {
  inquiry: MallInquiry
  sort: MallInquirySort
  quotes: MallInquiryQuote[]
}

// A merchant that can be asked on the inquiry form.
export interface MallInquiryOffer {
  offerId: string
  merchant: MallInquiryMerchantRef
  deliveryNote: string | null
  // false: no online payment, the merchant cannot be asked.
  canInquire: boolean
}

export interface MallInquiryInput {
  productId: string
  requirement: string
  quantity: number
  cityCode: string
  expectedDate: string | null
  offerIds: string[]
}

export const MALL_INQUIRY_REQUIREMENT_MAX = 200
export const MALL_INQUIRY_MIN_QUANTITY = 1
export const MALL_INQUIRY_MAX_QUANTITY = 99
export const MALL_INQUIRY_MAX_MERCHANTS = 3
export const MALL_INQUIRY_PAGE_SIZE = 20
