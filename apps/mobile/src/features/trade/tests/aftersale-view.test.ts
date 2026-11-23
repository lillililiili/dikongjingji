import { describe, expect, it } from 'vitest'

import {
  AFTERSALE_HEAD_COLORS,
  aftersaleBarButtons,
  aftersaleCardLine,
  aftersaleGoods,
  aftersaleHandler,
  canRefreshAftersale,
  classifyAftersaleFailure,
  describeAftersaleHead,
  describeAftersaleInfo,
  describeAftersaleSteps,
  evidenceSlots,
  refuseRows,
  returnAddressText,
  returnTips,
  waybillNote,
} from '../application/aftersale-view'
import { AGREED, LINE, RECEIVED, REFUNDED, SHIPPED, SUBMITTED, aftersale, event } from './aftersale-fixtures'

const WAYBILL = { carrierName: '示例快递', trackingNo: '2026110600012', shippedAt: SHIPPED }
const timeline = {
  agreed: [event('SUBMIT', 'PENDING_REVIEW', SUBMITTED), event('AGREE', 'AWAITING_RETURN', AGREED)],
  shipped: [
    event('SUBMIT', 'PENDING_REVIEW', SUBMITTED),
    event('AGREE', 'AWAITING_RETURN', AGREED),
    event('SHIP_RETURN', 'RETURN_SHIPPED', SHIPPED),
  ],
}

function stepsOf(input: Parameters<typeof aftersale>[0]) {
  return describeAftersaleSteps(aftersale(input)).map((step) => [step.title, step.sub, step.kind, step.line])
}

describe('aftersale status head (board AftersaleFlow status table)', () => {
  it('waits for the merchant, with the deadline when the server gives one', () => {
    expect(describeAftersaleHead(aftersale())).toEqual({ title: '等待商家处理', sub: '处理时限以平台交易协议为准', color: AFTERSALE_HEAD_COLORS.ORANGE })
    expect(describeAftersaleHead(aftersale({ dueAt: '2026-11-07T01:12:00Z' })).sub).toBe('商家需在 11/07 09:12 前处理，逾期自动同意')
  })

  it('asks for the goods back, then waits for the merchant to receive them', () => {
    expect(describeAftersaleHead(aftersale({ status: 'AWAITING_RETURN', dueAt: '2026-11-12T07:40:00Z' })))
      .toEqual({ title: '商家已同意，请寄回商品', sub: '请在 11/12 15:40 前寄回并填写运单', color: '#ba470b' })
    expect(describeAftersaleHead(aftersale({ status: 'RETURN_SHIPPED' })).title).toBe('等待商家收货')
  })

  it('says how much is on its way back and when it is done', () => {
    expect(describeAftersaleHead(aftersale({ status: 'REFUNDING', refundMinor: 129900 })).sub).toBe('商家已确认收货，¥1,299 正在原路退回')
    expect(describeAftersaleHead(aftersale({ status: 'REFUNDING', kind: 'REFUND_ONLY', reason: 'NOT_WANTED' })).sub).toBe('¥1,299 正在原路退回')
    expect(describeAftersaleHead(aftersale({ status: 'REFUNDED', refundMinor: 100000 })))
      .toEqual({ title: '已退款', sub: '¥1,000 已原路退回，本次售后已完成', color: '#11804a' })
  })

  it('says red when the merchant refused, grey when it was revoked or closed', () => {
    expect(describeAftersaleHead(aftersale({ status: 'REJECTED' }))).toEqual({ title: '商家未同意退货', sub: '这次申请没有通过，不会发起退款', color: '#d64f67' })
    expect(describeAftersaleHead(aftersale({ status: 'RECEIPT_REJECTED' })).title).toBe('验货未通过')
    expect(describeAftersaleHead(aftersale({ status: 'REVOKED' })).color).toBe('#536178')
    expect(describeAftersaleHead(aftersale({ status: 'CLOSED' })).title).toBe('已关闭')
    expect(describeAftersaleHead(aftersale({ status: 'REFUND_FAILED' })).title).toBe('退款异常')
  })
})

describe('aftersale info rows', () => {
  it('shows the amount and where it goes while the case runs', () => {
    expect(describeAftersaleInfo(aftersale()).map((row) => [row.label, row.value])).toEqual([
      ['退款金额', '¥1,299'],
      ['退款去向', '经平台支付渠道原路退回'],
    ])
  })

  it('adds the arrival time and the channel transaction once refunded', () => {
    const refunded = aftersale({
      status: 'REFUNDED',
      refundMinor: 129900,
      refund: { status: 'SUCCEEDED', amountMinor: 129900, succeededAt: REFUNDED, channelTransactionId: '4200000001' },
    })
    expect(describeAftersaleInfo(refunded).map((row) => [row.label, row.value])).toEqual([
      ['退款金额', '¥1,299'],
      ['退款去向', '经平台支付渠道原路退回'],
      ['到账时间', '11/09 09:30'],
      ['交易号', '4200000001'],
    ])
  })

  it('has none after a refusal, a revocation or a closing', () => {
    for (const status of ['REJECTED', 'RECEIPT_REJECTED', 'REVOKED', 'CLOSED'] as const) {
      expect(describeAftersaleInfo(aftersale({ status }))).toEqual([])
    }
  })
})

describe('aftersale progress (board done / cur / todo / end nodes)', () => {
  it('waits on the merchant review with three steps to come', () => {
    expect(stepsOf({})).toEqual([
      ['提交申请', '11/05 09:12 · 已完成', 'done', '#ff6a00'],
      ['商家审核', '进行中', 'cur', '#e5ebf3'],
      ['寄回商品', '', 'todo', '#e5ebf3'],
      ['商家确认收货', '', 'todo', '#e5ebf3'],
      ['退款原路退回', '经平台支付渠道', 'todo', 'transparent'],
    ])
  })

  it('asks for the goods back once agreed', () => {
    expect(stepsOf({ status: 'AWAITING_RETURN', timeline: timeline.agreed, reviewedAt: AGREED })).toEqual([
      ['提交申请', '11/05 09:12 · 已完成', 'done', '#ff6a00'],
      ['商家审核', '11/05 15:40 · 已同意退货', 'done', '#ff6a00'],
      ['寄回商品', '等你寄回并填写运单', 'cur', '#e5ebf3'],
      ['商家确认收货', '', 'todo', '#e5ebf3'],
      ['退款原路退回', '经平台支付渠道', 'todo', 'transparent'],
    ])
  })

  it('says when the merchant agreed by itself after the deadline', () => {
    const steps = stepsOf({ status: 'AWAITING_RETURN', timeline: [event('SUBMIT', 'PENDING_REVIEW', SUBMITTED), event('AUTO_AGREE', 'AWAITING_RETURN', AGREED)] })
    expect(steps[1]).toEqual(['商家审核', '11/05 15:40 · 逾期自动同意', 'done', '#ff6a00'])
  })

  it('runs the refund to the end', () => {
    const done = stepsOf({
      status: 'REFUNDED',
      timeline: [
        ...timeline.shipped,
        event('CONFIRM_RECEIPT', 'REFUNDING', RECEIVED),
        event('REFUND_SUCCEEDED', 'REFUNDED', REFUNDED),
      ],
    })
    expect(done.map((step) => step[2])).toEqual(['done', 'done', 'done', 'done', 'done'])
    expect(done[2][1]).toBe('11/06 10:05 · 运单已提交')
    expect(done[3][1]).toBe('11/08 14:20 · 验货通过')
    expect(done[4]).toEqual(['退款原路退回', '11/09 09:30 · 已到账 · 经平台支付渠道', 'done', 'transparent'])
    const refunding = stepsOf({ status: 'REFUNDING', timeline: [...timeline.shipped, event('AUTO_REFUND', 'REFUNDING', RECEIVED)] })
    expect(refunding[3][1]).toBe('11/08 14:20 · 商家逾期未处理，自动退款')
    expect(refunding[4]).toEqual(['退款原路退回', '退款中 · 经平台支付渠道', 'cur', 'transparent'])
  })

  it('ends the line in grey where the merchant refused or the buyer revoked', () => {
    expect(stepsOf({ status: 'REJECTED', timeline: [event('SUBMIT', 'PENDING_REVIEW', SUBMITTED), event('REJECT', 'REJECTED', AGREED)] })).toEqual([
      ['提交申请', '11/05 09:12 · 已完成', 'done', '#c9d3e1'],
      ['商家审核', '11/05 15:40 · 未同意', 'end', 'transparent'],
    ])
    const inspected = stepsOf({ status: 'RECEIPT_REJECTED', timeline: [...timeline.shipped, event('REJECT_RECEIPT', 'RECEIPT_REJECTED', RECEIVED)] })
    expect(inspected.map((step) => step[2])).toEqual(['done', 'done', 'done', 'end'])
    expect(inspected[2][3]).toBe('#c9d3e1')
    expect(inspected[3][1]).toBe('11/08 14:20 · 验货未通过')
    expect(stepsOf({ status: 'REVOKED', timeline: [event('SUBMIT', 'PENDING_REVIEW', SUBMITTED), event('REVOKE', 'REVOKED', AGREED)] }))
      .toEqual([
        ['提交申请', '11/05 09:12 · 已完成', 'done', '#c9d3e1'],
        ['撤销申请', '11/05 15:40 · 已撤销', 'end', 'transparent'],
      ])
    expect(stepsOf({ status: 'CLOSED', timeline: [...timeline.agreed, event('CLOSE_OVERDUE', 'CLOSED', SHIPPED)] })[2])
      .toEqual(['寄回商品', '11/06 10:05 · 逾期未寄回，已关闭', 'end', 'transparent'])
  })

  it('has two steps for a refund before shipping', () => {
    expect(stepsOf({ kind: 'REFUND_ONLY', reason: 'NOT_WANTED', status: 'REFUNDING' })).toEqual([
      ['提交申请', '11/05 09:12 · 已完成', 'done', '#ff6a00'],
      ['退款原路退回', '退款中 · 经平台支付渠道', 'cur', 'transparent'],
    ])
  })
})

describe('aftersale bottom bar (only what the server allows)', () => {
  it('pending review: contact, revoke, view the order', () => {
    expect(aftersaleBarButtons(aftersale(), true)).toEqual(['CONTACT_MERCHANT', 'REVOKE', 'VIEW_ORDER'])
    expect(aftersaleBarButtons(aftersale(), false)).toEqual(['REVOKE', 'VIEW_ORDER'])
  })

  it('awaiting return: revoke and fill in the waybill, no 查看订单', () => {
    const awaiting = aftersale({ status: 'AWAITING_RETURN', actions: { canRevoke: true, canSubmitReturn: true, canEditReturn: false, canReapply: false } })
    expect(aftersaleBarButtons(awaiting, true)).toEqual(['CONTACT_MERCHANT', 'REVOKE', 'SUBMIT_RETURN'])
  })

  it('shipped: change the waybill; refused: platform support and reapply', () => {
    const shipped = aftersale({ status: 'RETURN_SHIPPED', actions: { canRevoke: false, canSubmitReturn: false, canEditReturn: true, canReapply: false } })
    expect(aftersaleBarButtons(shipped, true)).toEqual(['CONTACT_MERCHANT', 'EDIT_RETURN', 'VIEW_ORDER'])
    const refused = aftersale({ status: 'REJECTED', actions: { canRevoke: false, canSubmitReturn: false, canEditReturn: false, canReapply: true } })
    expect(aftersaleBarButtons(refused, true)).toEqual(['CONTACT_MERCHANT', 'CONTACT_PLATFORM', 'REAPPLY'])
    const inspected = aftersale({ status: 'RECEIPT_REJECTED', actions: { canRevoke: false, canSubmitReturn: false, canEditReturn: false, canReapply: false } })
    expect(aftersaleBarButtons(inspected, true)).toEqual(['CONTACT_MERCHANT', 'CONTACT_PLATFORM', 'VIEW_ORDER'])
  })

  it('revoked: no contact, only 查看订单 (or 重新申请)', () => {
    const revoked = aftersale({ status: 'REVOKED', actions: { canRevoke: false, canSubmitReturn: false, canEditReturn: false, canReapply: false } })
    expect(aftersaleBarButtons(revoked, true)).toEqual(['VIEW_ORDER'])
  })

  it('offers 刷新进度 only while waiting on someone else', () => {
    expect(canRefreshAftersale(aftersale())).toBe(true)
    expect(canRefreshAftersale(aftersale({ status: 'AWAITING_RETURN' }))).toBe(false)
    expect(canRefreshAftersale(aftersale({ status: 'RETURN_SHIPPED' }))).toBe(true)
    expect(canRefreshAftersale(aftersale({ status: 'REFUNDED' }))).toBe(false)
  })
})

describe('aftersale blocks', () => {
  it('writes the return address, its tips and copies it in one line', () => {
    const awaiting = aftersale({
      status: 'AWAITING_RETURN',
      dueAt: '2026-11-12T07:40:00Z',
      returnAddress: { contactName: '售后组', phone: '0546-0000000', address: '东营区 示例路 120 号', note: null },
    })
    expect(returnAddressText(awaiting)).toBe('售后组 0546-0000000 东营区 示例路 120 号')
    expect(returnAddressText(aftersale())).toBeNull()
    expect(returnTips(awaiting)).toEqual([
      '请在 11/12 15:40 前寄回，逾期申请自动关闭',
      '商品、配件和包装保持完整，包裹内附订单号',
      '寄回运费按销售方规则承担',
    ])
  })

  it('notes when the waybill went in and when the merchant signed for it', () => {
    expect(waybillNote(aftersale({ status: 'RETURN_SHIPPED', returnWaybill: WAYBILL }))).toBe('11/06 10:05 已提交 · 商家确认收货前可修改')
    expect(waybillNote(aftersale({ status: 'RECEIPT_REJECTED', returnWaybill: WAYBILL, receivedAt: RECEIVED }))).toBe('11/08 14:20 商家已签收')
  })

  it('shows the merchant’s reasons and the evidence counts on a refusal', () => {
    const refused = aftersale({
      status: 'REJECTED',
      rejectReason: '凭证不足，无法确认商品未拆封',
      merchantNote: '请补充商品和外包装照片后重新申请',
      images: [{ imageId: 'i-1', slot: 'PRODUCT' }],
    })
    expect(refuseRows(refused)).toEqual([
      { label: '拒绝原因', value: '凭证不足，无法确认商品未拆封' },
      { label: '商家说明', value: '请补充商品和外包装照片后重新申请' },
    ])
    expect(refuseRows(aftersale())).toEqual([])
    expect(evidenceSlots(refused).map((slot) => [slot.label, slot.note, slot.noteColor])).toEqual([
      ['商品照片', '已上传 1 张', '#11804a'],
      ['包装照片', '未上传', '#ba470b'],
      ['其他凭证', '未上传', '#738096'],
    ])
  })

  it('names the handler: the after-sales party, else the merchant', () => {
    expect(aftersaleHandler({ name: '示例机器人商家', aftersaleParty: null })).toBe('示例机器人商家')
    expect(aftersaleHandler({ name: '示例机器人商家', aftersaleParty: '示例机器人商家 · 厂家保修' })).toBe('示例机器人商家 · 厂家保修')
  })

  it('stands for several lines with the first one', () => {
    expect(aftersaleGoods([LINE])).toEqual({ name: '编程机器人探索套装', spec: '标准套装（1 台 + 数字课程） × 1' })
    expect(aftersaleGoods([LINE, { ...LINE, lineId: 'l-2', quantity: 2 }])).toEqual({ name: '编程机器人探索套装 等 2 种商品', spec: '共 3 件' })
  })

  it('writes the list card line (board AftersaleList)', () => {
    expect(aftersaleCardLine(aftersale({ status: 'AWAITING_RETURN', dueAt: '2026-11-12T07:40:00Z' })))
      .toEqual({ text: '商家已同意，请在 11/12 15:40 前寄回', urgent: true })
    expect(aftersaleCardLine(aftersale({ status: 'REFUNDED', refundedAt: REFUNDED })).text).toBe('11/09 09:30 已原路退回')
    expect(aftersaleCardLine(aftersale({
      status: 'REJECTED',
      rejectReason: '凭证不足',
      actions: { canRevoke: false, canSubmitReturn: false, canEditReturn: false, canReapply: true },
    })).text).toBe('凭证不足，可补充后重新申请')
    expect(aftersaleCardLine(aftersale({ status: 'REVOKED' })).text).toBe('你已撤销这次申请')
  })
})

describe('aftersale failures in Chinese', () => {
  const failure = (status: number, code: string | null, details: Record<string, unknown> | null = null) =>
    Object.assign(new Error('x'), { status, code, details })
  const messageOf = (error: unknown) => {
    const classified = classifyAftersaleFailure(error)
    return classified.kind === 'UNAUTHORIZED' ? null : classified.message
  }

  it('names why a kind or a reason cannot be chosen now', () => {
    expect(classifyAftersaleFailure(failure(409, 'TRADE_AFTERSALE_NOT_ELIGIBLE', { reason: 'WINDOW_PASSED' })))
      .toEqual({ kind: 'CHANGED', message: '现在不能这样申请：已过申请时限' })
  })

  it('reads a changed case, an open case, bad pictures and the channel', () => {
    expect(classifyAftersaleFailure(failure(412, 'TRADE_AFTERSALE_VERSION_CONFLICT')).kind).toBe('CHANGED')
    expect(classifyAftersaleFailure(failure(409, 'TRADE_AFTERSALE_ALREADY_OPEN')))
      .toEqual({ kind: 'CHANGED', message: '这笔订单已有一张售后单在处理中' })
    expect(messageOf(failure(422, 'TRADE_AFTERSALE_IMAGE_INVALID'))).toBe('凭证图片无效或已用过，请重新上传')
    expect(messageOf(failure(429, 'TRADE_AFTERSALE_UPLOAD_LIMITED'))).toBe('上传太频繁，请稍后再试')
    expect(messageOf(failure(503, 'TRADE_CHANNEL_UNAVAILABLE'))).toBe('微信支付暂不可用，请稍后再试')
    expect(classifyAftersaleFailure(failure(401, 'COMMON_UNAUTHORIZED'))).toEqual({ kind: 'UNAUTHORIZED' })
    expect(messageOf(failure(0, 'NETWORK_ERROR'))).toBe('网络连接失败，请稍后重试')
  })
})
