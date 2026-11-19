import {
  RENTAL_MAX_DAYS,
  RENTAL_MAX_QUANTITY,
  RENTAL_MIN_DAYS,
  RENTAL_MIN_QUANTITY,
  RENTAL_NOTE_MAX_LENGTH,
  type RentalHandoverMode,
  type RentalInquiryInput,
  type RentalOfferView,
  type RentalReturnMode,
} from '../domain/rental'
import { addRentalDays, beijingToday, formatMonthDay, formatRentalAmount, weekdayOf } from './rental-format'

// What the inquiry form holds before it is sent (RentalInquiry board).
export interface RentalInquiryDraft {
  quantity: number
  startDate: string
  days: number
  handoverMode: RentalHandoverMode
  returnMode: RentalReturnMode
  addressId: string | null
  note: string
}

// The board's quick choices; the stepper next to them covers 1–90.
export const RENTAL_DAY_CHOICES = [3, 5, 7, 30] as const

// How many start days the date sheet offers, from tomorrow.
export const RENTAL_START_CHOICES = 12

export function earliestRentalStart(now: Date): string {
  return addRentalDays(beijingToday(now), 1)
}

export function createRentalInquiryDraft(now: Date): RentalInquiryDraft {
  return {
    quantity: 1,
    startDate: earliestRentalStart(now),
    days: 5,
    handoverMode: 'DELIVERY',
    returnMode: 'COLLECT',
    addressId: null,
    note: '',
  }
}

export function clampRentalQuantity(value: number): number {
  return Math.min(RENTAL_MAX_QUANTITY, Math.max(RENTAL_MIN_QUANTITY, Math.round(value)))
}

export function clampRentalDays(value: number): number {
  return Math.min(RENTAL_MAX_DAYS, Math.max(RENTAL_MIN_DAYS, Math.round(value)))
}

// The last day of use: start + days − 1.
export function rentalEndDate(startDate: string, days: number): string {
  return addRentalDays(startDate, days - 1)
}

export interface RentalDateOption {
  date: string
  monthDay: string
  weekday: string
}

export function rentalStartOptions(now: Date, count: number = RENTAL_START_CHOICES): RentalDateOption[] {
  const first = earliestRentalStart(now)
  return Array.from({ length: count }, (_, index) => {
    const date = addRentalDays(first, index)
    return { date, monthDay: formatMonthDay(date), weekday: weekdayOf(date) }
  })
}

// "11/22 周六"
export function formatRentalDay(date: string): string {
  return `${formatMonthDay(date)} ${weekdayOf(date)}`
}

/**
 * The reference rent on the form: the listed day price × days × units, marked 起 because the real
 * amount is what the merchant quotes. Only a day price can be multiplied out.
 */
export function rentalReferenceRent(
  offer: Pick<RentalOfferView, 'amountMinor' | 'unit'>,
  days: number,
  quantity: number,
): { formula: string; amount: string } | null {
  if (offer.amountMinor === null || offer.unit !== 'DAY') return null
  return {
    formula: `${formatRentalAmount(offer.amountMinor)} × ${days} 天 × ${quantity} 台`,
    amount: `${formatRentalAmount(offer.amountMinor * days * quantity)} 起`,
  }
}

export function rentalHandoverNote(mode: RentalHandoverMode, offer: Pick<RentalOfferView, 'merchantName' | 'fulfilledFrom'>): string {
  if (mode === 'DELIVERY') return '商家送到使用地点，现场核对设备和配件后交接'
  return `到${offer.fulfilledFrom || offer.merchantName}取机，现场核对后交接`
}

export function rentalReturnNote(mode: RentalReturnMode): string {
  return mode === 'COLLECT' ? '到期由商家上门取回，核对后录入归还记录' : '到期送回门店，核对后录入归还记录'
}

// The first thing that stops the form from being sent, in page order; null when it can go.
export function rentalDraftProblem(draft: RentalInquiryDraft, now: Date): string | null {
  if (draft.startDate < earliestRentalStart(now)) return '起租日最早是明天'
  if (!draft.addressId) return '请选择使用地点'
  if (draft.note.trim().length > RENTAL_NOTE_MAX_LENGTH) return `补充说明不超过 ${RENTAL_NOTE_MAX_LENGTH} 字`
  return null
}

export function toRentalInquiryInput(offerId: string, draft: RentalInquiryDraft): RentalInquiryInput {
  return {
    offerId,
    quantity: clampRentalQuantity(draft.quantity),
    startDate: draft.startDate,
    days: clampRentalDays(draft.days),
    handoverMode: draft.handoverMode,
    returnMode: draft.returnMode,
    addressId: draft.addressId ?? '',
    note: draft.note.trim(),
  }
}

// Two submissions with the same content are the same inquiry and share an idempotency key.
export function rentalInquirySignature(input: RentalInquiryInput): string {
  return [input.offerId, input.quantity, input.startDate, input.days, input.handoverMode, input.returnMode, input.addressId, input.note]
    .join('|')
}

export function createRentalKey(): string {
  const hex = Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16))
  hex[12] = '4'
  hex[16] = (8 + Math.floor(Math.random() * 4)).toString(16)
  const text = hex.join('')
  return `${text.slice(0, 8)}-${text.slice(8, 12)}-${text.slice(12, 16)}-${text.slice(16, 20)}-${text.slice(20)}`
}

/**
 * One idempotency key per distinct inquiry: tapping 提交询价 again after a network failure sends
 * the same key, so a request that did arrive is answered with the inquiry it already made.
 */
export function createRentalKeyHolder(createKey: () => string = createRentalKey) {
  let signature: string | null = null
  let key = ''
  return {
    keyFor(input: RentalInquiryInput): string {
      const next = rentalInquirySignature(input)
      if (next !== signature) {
        signature = next
        key = createKey()
      }
      return key
    },
  }
}

export interface RentalSubmitFailureInput {
  status: number
  code: string | null
  message: string
}

export type RentalSubmitFailure =
  // The offer cannot be asked about from here at all: the form is closed with this text.
  | { kind: 'BLOCKED'; message: string }
  // The chosen address is gone: read the address book again.
  | { kind: 'ADDRESS'; message: string }
  | { kind: 'UNAUTHORIZED' }
  | { kind: 'RETRY'; message: string }

export function describeRentalSubmitFailure(failure: RentalSubmitFailureInput | null): RentalSubmitFailure {
  if (!failure) return { kind: 'RETRY', message: '没有提交成功，请稍后重试' }
  if (failure.status === 401 || failure.code === 'COMMON_UNAUTHORIZED') return { kind: 'UNAUTHORIZED' }
  switch (failure.code) {
    case 'RENTAL_PAYMENT_UNAVAILABLE':
      return { kind: 'BLOCKED', message: '这家出租方还没有开通在线收款，暂时不能在线询价，可以换一家商家' }
    case 'RENTAL_OFFER_UNAVAILABLE':
      return { kind: 'BLOCKED', message: '这件设备已下架，或不在使用地点所在城市出租' }
    case 'RENTAL_ADDRESS_NOT_FOUND':
      return { kind: 'ADDRESS', message: '所选地址已不存在，请重新选择使用地点' }
    case 'RENTAL_IDEMPOTENCY_KEY_REUSED':
      return { kind: 'RETRY', message: '内容有变化，请再提交一次' }
    default:
      break
  }
  if (failure.status === 404) return { kind: 'BLOCKED', message: '这件设备已下架，或不在使用地点所在城市出租' }
  if (failure.status === 0) return { kind: 'RETRY', message: '网络连接失败，请稍后重试' }
  return { kind: 'RETRY', message: failure.message || '没有提交成功，请稍后重试' }
}
