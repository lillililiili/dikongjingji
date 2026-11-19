import { toBeijingDate, DAY_MS } from '../../../shared/time/beijing-time'

import {
  MALL_INQUIRY_MAX_MERCHANTS,
  MALL_INQUIRY_MAX_QUANTITY,
  MALL_INQUIRY_MIN_QUANTITY,
  MALL_INQUIRY_REQUIREMENT_MAX,
  type MallInquiryBuyer,
  type MallInquiryInput,
  type MallInquiryOffer,
} from '../domain/inquiry'

// What the inquiry form (PdInquiry board) holds before it is sent.
export interface MallInquiryDraft {
  requirement: string
  quantity: number
  // YYYY-MM-DD (Beijing); null: not given.
  expectedDate: string | null
  offerIds: string[]
}

/**
 * A new form asks every merchant that can be asked (up to three, in the server's order); the
 * customer unticks the ones they do not want, as on the board where two are ticked.
 */
export function createMallInquiryDraft(offers: MallInquiryOffer[]): MallInquiryDraft {
  return {
    requirement: '',
    quantity: 1,
    expectedDate: null,
    offerIds: offers.filter((offer) => offer.canInquire).slice(0, MALL_INQUIRY_MAX_MERCHANTS).map((offer) => offer.offerId),
  }
}

// After the merchants are read again: drop the ones that can no longer be asked.
export function keepAskableOffers(draft: MallInquiryDraft, offers: MallInquiryOffer[]): MallInquiryDraft {
  const askable = new Set(offers.filter((offer) => offer.canInquire).map((offer) => offer.offerId))
  return { ...draft, offerIds: draft.offerIds.filter((id) => askable.has(id)) }
}

export function clampInquiryQuantity(value: number): number {
  if (!Number.isFinite(value)) return MALL_INQUIRY_MIN_QUANTITY
  return Math.min(MALL_INQUIRY_MAX_QUANTITY, Math.max(MALL_INQUIRY_MIN_QUANTITY, Math.round(value)))
}

export type OfferToggle =
  | { kind: 'CHANGED'; draft: MallInquiryDraft }
  | { kind: 'REFUSED'; message: string }

export function toggleInquiryOffer(draft: MallInquiryDraft, offer: MallInquiryOffer): OfferToggle {
  if (draft.offerIds.includes(offer.offerId)) {
    return { kind: 'CHANGED', draft: { ...draft, offerIds: draft.offerIds.filter((id) => id !== offer.offerId) } }
  }
  if (!offer.canInquire) return { kind: 'REFUSED', message: '这家商家暂未开通在线支付，不能发询价' }
  if (draft.offerIds.length >= MALL_INQUIRY_MAX_MERCHANTS) {
    return { kind: 'REFUSED', message: `最多发给 ${MALL_INQUIRY_MAX_MERCHANTS} 家商家` }
  }
  return { kind: 'CHANGED', draft: { ...draft, offerIds: [...draft.offerIds, offer.offerId] } }
}

// The earliest expected delivery date: tomorrow in Beijing.
export function earliestInquiryDate(now: Date): string {
  return toBeijingDate(new Date(now.getTime() + DAY_MS))
}

// The last date the picker offers: one year ahead.
export function latestInquiryDate(now: Date): string {
  return toBeijingDate(new Date(now.getTime() + 365 * DAY_MS))
}

// "12 月 15 日前"
export function formatExpectedDate(date: string | null): string {
  if (!date) return '不限'
  const [, month, day] = date.split('-').map(Number)
  return `${month} 月 ${day} 日前`
}

// The first thing that stops the form from being sent, in page order; null when it can go.
export function mallInquiryDraftProblem(draft: MallInquiryDraft, now: Date): string | null {
  const requirement = draft.requirement.trim()
  if (!requirement) return '请填写配置和用途'
  if (requirement.length > MALL_INQUIRY_REQUIREMENT_MAX) return `配置和用途不超过 ${MALL_INQUIRY_REQUIREMENT_MAX} 字`
  if (draft.expectedDate && draft.expectedDate < earliestInquiryDate(now)) return '期望交付日期最早是明天'
  if (draft.offerIds.length === 0) return '请至少选择一家商家'
  return null
}

export function toMallInquiryInput(productId: string, cityCode: string, draft: MallInquiryDraft): MallInquiryInput {
  return {
    productId,
    requirement: draft.requirement.trim(),
    quantity: clampInquiryQuantity(draft.quantity),
    cityCode,
    expectedDate: draft.expectedDate,
    offerIds: [...draft.offerIds],
  }
}

// Two submissions with the same content are the same inquiry and share an idempotency key.
export function mallInquirySignature(input: MallInquiryInput): string {
  return [
    input.productId,
    input.cityCode,
    input.quantity,
    input.expectedDate ?? '',
    [...input.offerIds].sort().join(','),
    input.requirement,
  ].join('|')
}

/**
 * One idempotency key per distinct inquiry: tapping 提交询价 again after a network failure sends
 * the same key, so a request that did arrive is answered with the inquiry it already made.
 */
export function createMallInquiryKeyHolder(createKey: () => string) {
  let signature: string | null = null
  let key = ''
  return {
    keyFor(input: MallInquiryInput): string {
      const next = mallInquirySignature(input)
      if (next !== signature) {
        signature = next
        key = createKey()
      }
      return key
    },
  }
}

export interface InquiryFailureInput {
  status: number
  code: string | null
  message: string
}

export type MallInquirySubmitFailure =
  | { kind: 'UNAUTHORIZED' }
  // The product is not an inquiry product here any more: the form is closed with this text.
  | { kind: 'BLOCKED'; message: string }
  // A chosen merchant went away or cannot collect payment: read the merchants again.
  | { kind: 'MERCHANTS'; message: string }
  | { kind: 'RETRY'; message: string }

export function describeMallInquirySubmitFailure(failure: InquiryFailureInput | null): MallInquirySubmitFailure {
  if (!failure) return { kind: 'RETRY', message: '没有提交成功，请稍后重试' }
  if (failure.status === 401 || failure.code === 'COMMON_UNAUTHORIZED') return { kind: 'UNAUTHORIZED' }
  switch (failure.code) {
    case 'MALL_INQUIRY_MERCHANT_UNAVAILABLE':
      return { kind: 'MERCHANTS', message: '有商家暂未开通在线支付，请重新选择' }
    case 'MALL_INQUIRY_OFFER_UNAVAILABLE':
      return { kind: 'MERCHANTS', message: '有商家已不在本城销售这件商品，请重新选择' }
    case 'MALL_INQUIRY_IDEMPOTENCY_KEY_REUSED':
      return { kind: 'RETRY', message: '内容有变化，请再提交一次' }
    default:
      break
  }
  if (failure.status === 404) return { kind: 'BLOCKED', message: '这件商品已下架，或本城暂不销售' }
  if (failure.status === 0) return { kind: 'RETRY', message: '网络连接失败，请稍后重试' }
  return { kind: 'RETRY', message: failure.message || '没有提交成功，请稍后重试' }
}

export interface MallInquiryBuyerView {
  // "企业 · 东营示例物流公司" / "个人 · 王女士"
  text: string
  // The header badge of the quotes page: "企业 · 东营示例物流公司" / "个人".
  badge: string
}

export function describeMallInquiryBuyer(buyer: MallInquiryBuyer): MallInquiryBuyerView {
  if (buyer.kind === 'ORGANIZATION' && buyer.organizationName) {
    const text = `企业 · ${buyer.organizationName}`
    return { text, badge: text }
  }
  return { text: buyer.contactName ? `个人 · ${buyer.contactName}` : '个人', badge: '个人' }
}

// "示例机器人商家" row note on the picker: the merchant kind and its delivery note.
export function inquiryOfferNote(offer: MallInquiryOffer, kindLabel: string): string {
  if (!offer.canInquire) return '暂未开通在线支付'
  return [kindLabel, offer.deliveryNote].filter(Boolean).join(' · ')
}
