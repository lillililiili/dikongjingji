// Mirrors contracts/openapi-rental.yaml (1.1.0; 1.0.0 was step F3). Amounts are in fen, dates are Beijing
// calendar days (YYYY-MM-DD), instants are ISO strings.

export type RentalInquiryStatus = 'PENDING' | 'QUOTED' | 'EXPIRED' | 'CONFIRMED' | 'DECLINED' | 'CLOSED'

export const RENTAL_INQUIRY_STATUSES: readonly RentalInquiryStatus[] = [
  'PENDING', 'QUOTED', 'EXPIRED', 'CONFIRMED', 'DECLINED', 'CLOSED',
]

// CUSTOMER: closed by the customer; NO_QUOTE: no quote within 7 days.
export type RentalCloseReason = 'CUSTOMER' | 'NO_QUOTE'

export type RentalOrderStatus =
  | 'PENDING_PAYMENT'
  | 'CLOSED'
  | 'AWAITING_HANDOVER'
  | 'HANDOVER_RECORDED'
  | 'IN_USE'
  | 'RETURN_RECORDED'
  | 'ENDED'
  | 'CANCELLED'

export const RENTAL_ORDER_STATUSES: readonly RentalOrderStatus[] = [
  'PENDING_PAYMENT', 'CLOSED', 'AWAITING_HANDOVER', 'HANDOVER_RECORDED', 'IN_USE', 'RETURN_RECORDED', 'ENDED', 'CANCELLED',
]

export type RentalHandoverMode = 'DELIVERY' | 'PICKUP'
export type RentalReturnMode = 'COLLECT' | 'DROP_OFF'
export type RentalLesseeKind = 'ORGANIZATION' | 'PERSONAL'
export type RentalReferenceUnit = 'DAY' | 'HALF_DAY' | 'ITEM'
export type RentalRefundStatus = 'REQUESTED' | 'PROCESSING' | 'SUCCEEDED' | 'CLOSED' | 'ABNORMAL'

export interface RentalLessee {
  kind: RentalLesseeKind
  // The customer organization the inquiry is recorded under; null for a person.
  organizationName: string | null
  // The account's display name (经办人).
  contactName: string
}

export interface RentalMerchant {
  merchantId: string
  name: string
  // (1.1.0, R6-4) The platform reviewed the lessor's qualification: the green 资质已审核 tag.
  qualificationReviewed: boolean
}

// The device asked about, as it was when the inquiry was sent.
export interface RentalItem {
  offerId: string
  productId: string
  productName: string
  skuName: string
  coverUrl: string | null
  referenceAmountMinor: number | null
  referenceUnit: RentalReferenceUnit
}

// The place of use: a copy of the chosen delivery address.
export interface RentalPlace {
  addressId: string | null
  recipientName: string
  phone: string
  provinceName: string
  cityCode: string
  cityName: string
  detail: string
}

export interface RentalFee {
  name: string
  note: string | null
  // May be 0: included, not charged separately.
  amountMinor: number
}

export interface RentalQuote {
  version: number
  dailyRentMinor: number
  days: number
  quantity: number
  rentMinor: number
  fees: RentalFee[]
  totalMinor: number
  scheduleAvailable: boolean
  validUntil: string
  expired: boolean
  terms: string
  quotedAt: string
}

export interface RentalOrderSummary {
  rentalOrderId: string
  status: RentalOrderStatus
  tradeOrderId: string
}

export interface RentalInquiry {
  inquiryId: string
  status: RentalInquiryStatus
  closeReason: RentalCloseReason | null
  declineReason: string | null
  lessee: RentalLessee
  merchant: RentalMerchant
  item: RentalItem
  // The quote's numbers once there is a quote, else what was asked.
  quantity: number
  startDate: string
  endDate: string
  days: number
  handoverMode: RentalHandoverMode
  returnMode: RentalReturnMode
  place: RentalPlace
  note: string | null
  submittedAt: string
  quoteDeadline: string
  quote: RentalQuote | null
  // What the customer may do now, decided by the server.
  confirmable: boolean
  closable: boolean
  order: RentalOrderSummary | null
  updatedAt: string
  // Sent back as If-Match when confirming the contract.
  version: number
}

export interface RentalInquiryPage {
  total: number
  hasMore: boolean
  items: RentalInquiry[]
}

export interface RentalContract {
  contractNo: string
  confirmedAt: string
  quoteVersion: number
  lessee: RentalLessee
  lessor: RentalMerchant
  productName: string
  skuName: string
  quantity: number
  startDate: string
  endDate: string
  days: number
  dailyRentMinor: number
  rentMinor: number
  fees: RentalFee[]
  totalMinor: number
  handoverMode: RentalHandoverMode
  returnMode: RentalReturnMode
  place: RentalPlace
  terms: string
}

export interface RentalPayment {
  tradeOrderId: string
  amountMinor: number
  payBefore: string
  paidAt: string | null
}

// A handover or return record: written by the merchant, confirmed by the customer.
export interface RentalRecord {
  occurredAt: string
  note: string
  recordedAt: string
  // Null while it waits for the customer.
  confirmedAt: string | null
}

export interface RentalRefund {
  refundId: string
  amountMinor: number
  status: RentalRefundStatus
}

export interface RentalOrder {
  rentalOrderId: string
  inquiryId: string
  status: RentalOrderStatus
  contract: RentalContract
  payment: RentalPayment
  handoverRecord: RentalRecord | null
  returnRecord: RentalRecord | null
  rentStartedAt: string | null
  endedAt: string | null
  closedAt: string | null
  cancelledAt: string | null
  cancelReason: string | null
  refund: RentalRefund | null
  handoverConfirmable: boolean
  returnConfirmable: boolean
  createdAt: string
  updatedAt: string
  version: number
}

export interface RentalOrderPage {
  total: number
  hasMore: boolean
  items: RentalOrder[]
}

export interface RentalConfirmation {
  inquiryId: string
  rentalOrderId: string
  tradeOrderId: string
  payBefore: string
}

export interface RentalInquiryInput {
  offerId: string
  quantity: number
  startDate: string
  days: number
  handoverMode: RentalHandoverMode
  returnMode: RentalReturnMode
  addressId: string
  note: string
}

// The rental offer the inquiry form is about, read from the public mall product.
export interface RentalOfferView {
  offerId: string
  productId: string
  productName: string
  skuName: string
  coverUrl: string | null
  merchantName: string
  amountMinor: number | null
  unit: RentalReferenceUnit
  // "示例机器人商家 东营仓": where a pickup happens.
  fulfilledFrom: string | null
  // (catalog 1.2.0) The lessor can take payment online; without it the form cannot be sent.
  onlinePayment: boolean
}

export const RENTAL_MIN_QUANTITY = 1
export const RENTAL_MAX_QUANTITY = 10
export const RENTAL_MIN_DAYS = 1
export const RENTAL_MAX_DAYS = 90
export const RENTAL_NOTE_MAX_LENGTH = 200
export const RENTAL_PAGE_SIZE = 20
