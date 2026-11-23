import { describe, expect, it } from 'vitest'

import {
  applicationInput,
  applyAmountMinor,
  canChooseReason,
  canSubmitApplication,
  createApplyKeyHolder,
  initialChoice,
  kindChoices,
  reasonChoices,
  slotCount,
  slotRoom,
} from '../application/aftersale-apply'
import {
  initialWaybillDraft,
  waybillHint,
  waybillInput,
} from '../application/aftersale-waybill'
import { eligibility } from './aftersale-fixtures'

const NOT_SHIPPED = eligibility({
  orderStatus: 'PAID',
  shippedAt: null,
  kinds: [
    { kind: 'REFUND_ONLY', available: true, unavailableReason: null },
    { kind: 'RETURN_REFUND', available: false, unavailableReason: 'NOT_SHIPPED' },
  ],
  reasons: [
    { reason: 'NOT_WANTED', kind: 'REFUND_ONLY', available: true, unavailableReason: null },
    { reason: 'NO_REASON_7D', kind: 'RETURN_REFUND', available: false, unavailableReason: 'NOT_SHIPPED' },
  ],
})

describe('aftersale application (board AftersaleApply)', () => {
  it('greys out 仅退款 on a shipped order and says why', () => {
    expect(kindChoices(eligibility())).toEqual([
      { kind: 'REFUND_ONLY', title: '仅退款（未发货）', sub: '订单已发货，不可选', available: false },
      { kind: 'RETURN_REFUND', title: '退货退款', sub: '寄回商品，商家确认收货后退款', available: true },
    ])
    expect(kindChoices(NOT_SHIPPED).map((choice) => [choice.kind, choice.available, choice.sub])).toEqual([
      ['REFUND_ONLY', true, '商品还没发货，申请后全额原路退回'],
      ['RETURN_REFUND', false, '订单还没发货，不可选（可以仅退款）'],
    ])
  })

  it('lists only the reasons of the chosen kind, greying those that cannot be chosen', () => {
    const noReasonOff = eligibility({
      reasons: [
        { reason: 'NO_REASON_7D', kind: 'RETURN_REFUND', available: false, unavailableReason: 'NO_REASON_NOT_SUPPORTED' },
        { reason: 'NOT_AS_DESCRIBED', kind: 'RETURN_REFUND', available: true, unavailableReason: null },
        { reason: 'NOT_WANTED', kind: 'REFUND_ONLY', available: false, unavailableReason: 'ALREADY_SHIPPED' },
      ],
    })
    expect(reasonChoices(noReasonOff, 'RETURN_REFUND')).toEqual([
      { reason: 'NO_REASON_7D', label: '不想要了（未拆封，7 天无理由）', available: false, note: '订单里有商品不支持七天无理由' },
      { reason: 'NOT_AS_DESCRIBED', label: '商品与描述不符', available: true, note: null },
    ])
    expect(reasonChoices(noReasonOff, null)).toEqual([])
    expect(canChooseReason(noReasonOff, 'RETURN_REFUND', 'NO_REASON_7D')).toBe(false)
    // The first reason that can be chosen starts selected.
    expect(initialChoice(noReasonOff)).toEqual({ kind: 'RETURN_REFUND', reason: 'NOT_AS_DESCRIBED' })
    expect(initialChoice(NOT_SHIPPED)).toEqual({ kind: 'REFUND_ONLY', reason: 'NOT_WANTED' })
  })

  it('applies for what is left to refund, and only when kind and reason can be chosen', () => {
    expect(applyAmountMinor(eligibility({ maxRefundMinor: 99900 }))).toBe(99900)
    expect(canSubmitApplication({ eligibility: eligibility(), kind: 'RETURN_REFUND', reason: 'DAMAGED', uploading: false })).toBe(true)
    expect(canSubmitApplication({ eligibility: eligibility(), kind: 'RETURN_REFUND', reason: 'DAMAGED', uploading: true })).toBe(false)
    expect(canSubmitApplication({ eligibility: eligibility(), kind: 'REFUND_ONLY', reason: 'NOT_WANTED', uploading: false })).toBe(false)
    expect(canSubmitApplication({ eligibility: eligibility({ maxRefundMinor: 0 }), kind: 'RETURN_REFUND', reason: 'DAMAGED', uploading: false })).toBe(false)
    const nothing = eligibility({ kinds: [{ kind: 'RETURN_REFUND', available: false, unavailableReason: 'WINDOW_PASSED' }] })
    expect(initialChoice(nothing)).toEqual({ kind: null, reason: null })
  })

  it('counts the pictures per slot, three at most', () => {
    const images = [
      { imageId: 'a', slot: 'PRODUCT' as const, localPath: '/a' },
      { imageId: 'b', slot: 'PRODUCT' as const, localPath: '/b' },
      { imageId: 'c', slot: 'PACKAGE' as const, localPath: '/c' },
    ]
    expect(slotCount(images, 'PRODUCT')).toBe(2)
    expect(slotRoom(images, 'PRODUCT')).toBe(1)
    expect(slotRoom(images, 'OTHER')).toBe(3)
    expect(applicationInput({ kind: 'RETURN_REFUND', reason: 'DAMAGED', description: ' 外壳裂了 ', images }))
      .toEqual({ kind: 'RETURN_REFUND', reason: 'DAMAGED', description: '外壳裂了', requestedMinor: null, imageIds: ['a', 'b', 'c'] })
  })

  it('keeps one idempotency key per content: a retry sends the same key, a change a new one', () => {
    let n = 0
    const keys = createApplyKeyHolder(() => `k${++n}`)
    const input = applicationInput({ kind: 'RETURN_REFUND', reason: 'NO_REASON_7D', description: '', images: [] })
    expect(keys.keyFor('o-1', input)).toBe('k1')
    expect(keys.keyFor('o-1', input)).toBe('k1')
    expect(keys.keyFor('o-1', { ...input, description: '未拆封' })).toBe('k2')
    expect(keys.keyFor('o-1', { ...input, description: '未拆封', imageIds: ['i'] })).toBe('k3')
  })
})

describe('return waybill sheet (board AftersaleFlow)', () => {
  it('starts on 示例快递 and takes 4–40 letters, digits or dashes', () => {
    const draft = initialWaybillDraft(null)
    expect(draft).toEqual({ pick: 'SAMPLE', otherCarrier: '', trackingNo: '' })
    expect(waybillInput(draft)).toBeNull()
    expect(waybillInput({ ...draft, trackingNo: ' 2026110600012 ' })).toEqual({ carrierName: '示例快递', trackingNo: '2026110600012' })
    expect(waybillInput({ ...draft, trackingNo: '12' })).toBeNull()
    expect(waybillHint({ ...draft, trackingNo: '12 34' })).toBe('运单号为 4–40 位字母、数字或“-”')
  })

  it('asks for the name of another carrier', () => {
    expect(waybillInput({ pick: 'OTHER', otherCarrier: ' ', trackingNo: 'YT123456' })).toBeNull()
    expect(waybillHint({ pick: 'OTHER', otherCarrier: '', trackingNo: '' })).toBe('请填写承运商名称')
    expect(waybillInput({ pick: 'OTHER', otherCarrier: '圆通速递', trackingNo: 'YT123456' })).toEqual({ carrierName: '圆通速递', trackingNo: 'YT123456' })
  })

  it('brings back the submitted waybill when changing it', () => {
    expect(initialWaybillDraft({ carrierName: '示例快递', trackingNo: 'SF1', shippedAt: null })).toEqual({ pick: 'SAMPLE', otherCarrier: '', trackingNo: 'SF1' })
    expect(initialWaybillDraft({ carrierName: '圆通速递', trackingNo: 'YT1', shippedAt: null })).toEqual({ pick: 'OTHER', otherCarrier: '圆通速递', trackingNo: 'YT1' })
  })
})
