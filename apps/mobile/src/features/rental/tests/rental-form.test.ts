import { describe, expect, it } from 'vitest'

import {
  addRentalDays,
  daysBetween,
  describeRentalLessee,
  formatRentalAmount,
  formatRentalPeriod,
  formatRentalReference,
} from '../application/rental-format'
import {
  clampRentalDays,
  clampRentalQuantity,
  createRentalInquiryDraft,
  createRentalKeyHolder,
  describeRentalSubmitFailure,
  earliestRentalStart,
  formatRentalDay,
  rentalDraftProblem,
  rentalEndDate,
  rentalHandoverNote,
  rentalReferenceRent,
  rentalStartOptions,
  toRentalInquiryInput,
} from '../application/rental-form'
import { NOW } from './rental-fixtures'

const offer = { merchantName: '示例机器人商家', fulfilledFrom: '示例机器人商家 东营仓', amountMinor: 38_000, unit: 'DAY' as const }

describe('rental inquiry form', () => {
  it('starts tomorrow in Beijing, five days, one unit, delivered and collected', () => {
    // 23:30 on the 19th in Beijing is still the 19th; tomorrow is the 20th.
    expect(earliestRentalStart(new Date('2026-11-19T15:30:00Z'))).toBe('2026-11-20')
    // 00:30 on the 20th in Beijing (16:30 UTC the day before): tomorrow is the 21st.
    expect(earliestRentalStart(new Date('2026-11-19T16:30:00Z'))).toBe('2026-11-21')
    expect(createRentalInquiryDraft(NOW)).toEqual({
      quantity: 1, startDate: '2026-11-20', days: 5, handoverMode: 'DELIVERY', returnMode: 'COLLECT', addressId: null, note: '',
    })
  })

  it('works out the end day, the start choices and the day labels across a month end', () => {
    expect(rentalEndDate('2026-11-28', 5)).toBe('2026-12-02')
    expect(rentalEndDate('2026-11-28', 1)).toBe('2026-11-28')
    expect(addRentalDays('2026-12-31', 1)).toBe('2027-01-01')
    expect(daysBetween('2026-11-19', '2026-11-26')).toBe(7)
    const options = rentalStartOptions(NOW, 3)
    expect(options).toEqual([
      { date: '2026-11-20', monthDay: '11/20', weekday: '周五' },
      { date: '2026-11-21', monthDay: '11/21', weekday: '周六' },
      { date: '2026-11-22', monthDay: '11/22', weekday: '周日' },
    ])
    expect(formatRentalDay('2026-11-22')).toBe('11/22 周日')
    expect(formatRentalPeriod('2026-11-22', '2026-11-26', 5)).toBe('11/22–11/26 · 共 5 天')
  })

  it('keeps units within 1–10 and days within 1–90', () => {
    expect(clampRentalQuantity(0)).toBe(1)
    expect(clampRentalQuantity(11)).toBe(10)
    expect(clampRentalDays(0)).toBe(1)
    expect(clampRentalDays(91)).toBe(90)
  })

  it('multiplies out the reference rent only for a day price, marked 起', () => {
    expect(rentalReferenceRent(offer, 5, 2)).toEqual({ formula: '¥380 × 5 天 × 2 台', amount: '¥3,800 起' })
    expect(rentalReferenceRent({ amountMinor: 30_000, unit: 'HALF_DAY' }, 5, 1)).toBeNull()
    expect(rentalReferenceRent({ amountMinor: null, unit: 'DAY' }, 5, 1)).toBeNull()
    expect(formatRentalReference(38_000, 'DAY')).toBe('¥380/天 起')
    expect(formatRentalReference(null, 'DAY')).toBe('价格以商家报价为准')
    expect(formatRentalAmount(150)).toBe('¥1.50')
  })

  it('says where a pickup happens', () => {
    expect(rentalHandoverNote('DELIVERY', offer)).toBe('商家送到使用地点，现场核对设备和配件后交接')
    expect(rentalHandoverNote('PICKUP', offer)).toBe('到示例机器人商家 东营仓取机，现场核对后交接')
    expect(rentalHandoverNote('PICKUP', { ...offer, fulfilledFrom: null })).toBe('到示例机器人商家取机，现场核对后交接')
  })

  it('names the first problem before sending', () => {
    const draft = createRentalInquiryDraft(NOW)
    expect(rentalDraftProblem(draft, NOW)).toBe('请选择使用地点')
    expect(rentalDraftProblem({ ...draft, addressId: 'a-1', startDate: '2026-11-19' }, NOW)).toBe('起租日最早是明天')
    expect(rentalDraftProblem({ ...draft, addressId: 'a-1', note: 'x'.repeat(201) }, NOW)).toBe('补充说明不超过 200 字')
    expect(rentalDraftProblem({ ...draft, addressId: 'a-1' }, NOW)).toBeNull()
    expect(toRentalInquiryInput('o-1', { ...draft, addressId: 'a-1', note: ' 夜间 ' })).toMatchObject({ addressId: 'a-1', note: '夜间' })
  })

  it('reuses the idempotency key while the content is the same', () => {
    let next = 0
    const holder = createRentalKeyHolder(() => `k${++next}`)
    const input = toRentalInquiryInput('o-1', { ...createRentalInquiryDraft(NOW), addressId: 'a-1' })
    expect(holder.keyFor(input)).toBe('k1')
    expect(holder.keyFor({ ...input })).toBe('k1')
    expect(holder.keyFor({ ...input, days: 6 })).toBe('k2')
  })

  it('closes the form when the lessor cannot be paid online or the offer is gone', () => {
    expect(describeRentalSubmitFailure({ status: 409, code: 'RENTAL_PAYMENT_UNAVAILABLE', message: '' }).kind).toBe('BLOCKED')
    expect(describeRentalSubmitFailure({ status: 409, code: 'RENTAL_OFFER_UNAVAILABLE', message: '' }).kind).toBe('BLOCKED')
    expect(describeRentalSubmitFailure({ status: 422, code: 'RENTAL_ADDRESS_NOT_FOUND', message: '' }).kind).toBe('ADDRESS')
    expect(describeRentalSubmitFailure({ status: 401, code: null, message: '' }).kind).toBe('UNAUTHORIZED')
    expect(describeRentalSubmitFailure({ status: 0, code: null, message: '' })).toEqual({ kind: 'RETRY', message: '网络连接失败，请稍后重试' })
    expect(describeRentalSubmitFailure(null).kind).toBe('RETRY')
  })

  it('describes who rents', () => {
    expect(describeRentalLessee({ kind: 'ORGANIZATION', organizationName: '东营港务物流', contactName: '王女士' }))
      .toEqual({ tag: '企业', text: '东营港务物流（经办人 王女士）', badge: '企业 · 东营港务物流' })
    expect(describeRentalLessee({ kind: 'PERSONAL', organizationName: null, contactName: '王女士' }))
      .toEqual({ tag: '个人', text: '王女士', badge: '个人' })
  })
})
