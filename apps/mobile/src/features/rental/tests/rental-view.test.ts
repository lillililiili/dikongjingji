import { describe, expect, it } from 'vitest'

import { describeRentalInquiryCard, rentalCloseConfirm } from '../application/rental-inquiry-card'
import {
  describeRentalOrderCard,
  describeRentalOrderHead,
  rentalChain,
  rentalConfirmSheet,
  rentalContractSummary,
  rentalContractTag,
  rentalFeeRows,
  rentalHandoverView,
  rentalOrderAction,
  rentalReturnView,
} from '../application/rental-order-view'
import {
  describeRentalConfirmFailure,
  rentalPayNode,
  rentalQuoteFrame,
  rentalQuoteLines,
  rentalQuoteNotice,
  rentalScheduleRows,
  rentalTermRows,
} from '../application/rental-quote-view'
import { NOW, rentalInquiry, rentalOrder, rentalQuote } from './rental-fixtures'

const record = { occurredAt: '2026-11-22T01:30:00Z', note: '编号 DOG-0118，配件齐全', recordedAt: '2026-11-22T01:35:00Z', confirmedAt: null }

describe('rental quote page', () => {
  it('picks the frame of the board from the status and the quote', () => {
    expect(rentalQuoteFrame(rentalInquiry())).toBe('WAITING')
    expect(rentalQuoteFrame(rentalInquiry({ status: 'QUOTED', quote: rentalQuote() }))).toBe('OPEN')
    expect(rentalQuoteFrame(rentalInquiry({ status: 'QUOTED', quote: rentalQuote({ scheduleAvailable: false }) }))).toBe('UNAVAILABLE')
    // The server marks the quote expired before the job moves the inquiry.
    expect(rentalQuoteFrame(rentalInquiry({ status: 'QUOTED', quote: rentalQuote({ expired: true }) }))).toBe('EXPIRED')
    expect(rentalQuoteFrame(rentalInquiry({ status: 'EXPIRED' }))).toBe('EXPIRED')
    expect(rentalQuoteFrame(rentalInquiry({ status: 'CONFIRMED' }))).toBe('CONFIRMED')
    expect(rentalQuoteFrame(rentalInquiry({ status: 'DECLINED' }))).toBe('DECLINED')
    expect(rentalQuoteFrame(rentalInquiry({ status: 'CLOSED' }))).toBe('CLOSED')
  })

  it('lists the rent and every fee, an included fee as 含', () => {
    expect(rentalQuoteLines(rentalQuote())).toEqual([
      { key: 'rent', name: '租金', detail: '5 天 × ¥380 × 1 台', amount: '¥1,900', included: false },
      { key: 'fee-送达与取回', name: '送达与取回', detail: '东营市内', amount: '含', included: true },
      { key: 'fee-保险', name: '保险', detail: '', amount: '¥50', included: false },
    ])
  })

  it('writes the schedule and splits the terms like the board', () => {
    const rows = rentalScheduleRows(rentalInquiry({ quote: rentalQuote() }))
    expect(rows.map((row) => row.value)).toEqual([
      '11/22–11/26 · 共 5 天',
      '1 台 · 设备编号交接时确定',
      '商家送达 · 11/22 · 东营市 东营港 示例港路 6 号',
      '商家上门取回 · 11/26 前',
      '商家已确认这 5 天可以出租',
    ])
    expect(rentalTermRows(rentalQuote().terms)).toEqual([
      { key: 'term-0', label: '押金', text: '当前不在线收取。' },
      { key: 'term-1', label: '超期', text: '按天计费。' },
      { key: 'term-2', label: '', text: '设备编号交接时确定' },
    ])
  })

  it('says why nothing can be paid outside an open quote', () => {
    expect(rentalPayNode('OPEN')).toEqual({ state: '合同确认后一次付清', active: true })
    expect(rentalPayNode('EXPIRED').active).toBe(false)
    expect(rentalQuoteNotice(rentalInquiry(), 'WAITING')?.text).toContain('11/26 09:00 前没有报价，询价自动关闭')
    expect(rentalQuoteNotice(rentalInquiry({ declineReason: '已排满' }), 'DECLINED')?.text).toBe('原因：已排满')
    expect(rentalQuoteNotice(rentalInquiry({ closeReason: 'NO_QUOTE' }), 'CLOSED')?.text).toBe('7 天内商家没有报价，询价已自动关闭')
    expect(rentalQuoteNotice(rentalInquiry(), 'OPEN')).toBeNull()
  })

  it('reads the page again when the quote or the inquiry changed', () => {
    expect(describeRentalConfirmFailure({ status: 409, code: 'RENTAL_QUOTE_CHANGED', message: '' }))
      .toEqual({ message: '商家已更新报价，已刷新，请核对最新报价后再确认', reload: true })
    expect(describeRentalConfirmFailure({ status: 412, code: 'RENTAL_VERSION_CONFLICT', message: '' }).reload).toBe(true)
    expect(describeRentalConfirmFailure({ status: 412, code: null, message: '' }).reload).toBe(true)
    expect(describeRentalConfirmFailure({ status: 409, code: 'RENTAL_QUOTE_EXPIRED', message: '' }).reload).toBe(true)
    expect(describeRentalConfirmFailure({ status: 422, code: 'RENTAL_TERMS_NOT_AGREED', message: '' }).reload).toBe(false)
    expect(describeRentalConfirmFailure({ status: 401, code: null, message: '' }).unauthorized).toBe(true)
  })
})

describe('rental inquiry card (需求与报价)', () => {
  it('offers what the server allows for each status', () => {
    const actions = (overrides: Parameters<typeof rentalInquiry>[0]) =>
      describeRentalInquiryCard(rentalInquiry(overrides)).buttons.map((button) => button.label)
    expect(actions({})).toEqual(['关闭询价', '查看询价'])
    expect(actions({ status: 'QUOTED', quote: rentalQuote() })).toEqual(['关闭询价', '查看报价'])
    expect(actions({ status: 'EXPIRED', quote: rentalQuote({ expired: true }) })).toEqual(['关闭询价', '查看报价', '重新询价'])
    expect(actions({ status: 'CONFIRMED', closable: false, order: { rentalOrderId: 'r-1', status: 'IN_USE', tradeOrderId: 't-1' } }))
      .toEqual(['查看报价', '查看订单'])
    expect(actions({ status: 'CLOSED', closable: false })).toEqual(['查看报价', '重新询价'])
  })

  it('writes a line per status in the demand list tones', () => {
    const card = describeRentalInquiryCard(rentalInquiry({ status: 'QUOTED', quote: rentalQuote() }))
    expect(card).toMatchObject({
      kind: '询价单',
      publisher: '企业 · 东营港务物流',
      statusText: '报价中',
      tone: 'QUOTING',
      title: '巡检机器狗 租赁询价',
      meta: '设备租赁 · 东营市 · 1 台 · 5 天',
      line: '已收到报价 ¥1,950 · 有效期至 11/22 18:00',
    })
    expect(describeRentalInquiryCard(rentalInquiry()).line).toBe('等待 示例机器人商家 报价 · 截止 11/26 09:00')
    expect(describeRentalInquiryCard(rentalInquiry({ status: 'DECLINED', declineReason: '已排满' })).line).toBe('商家已拒绝：已排满')
    expect(describeRentalInquiryCard(rentalInquiry({ status: 'CLOSED', closeReason: 'CUSTOMER' })).line).toBe('你已关闭这份询价')
    expect(rentalCloseConfirm(rentalInquiry()).text).toBe('关闭后商家不再报价，需要时重新发起询价。')
    expect(rentalCloseConfirm(rentalInquiry({ quote: rentalQuote() })).text).toBe('关闭后这份报价失效，商家不再为你保留档期。')
  })
})

describe('rental order page', () => {
  it('heads every status and follows the six-step chain', () => {
    expect(describeRentalOrderHead(rentalOrder({ status: 'PENDING_PAYMENT', payment: { ...rentalOrder().payment, paidAt: null } }), NOW).title)
      .toBe('待付款 · 11/19 11:00 前付清')
    expect(describeRentalOrderHead(rentalOrder({ status: 'HANDOVER_RECORDED', handoverRecord: record }), NOW).text)
      .toBe('商家 11/22 09:30 已录入记录，确认后开始计租')
    expect(describeRentalOrderHead(rentalOrder({ status: 'IN_USE' }), NOW).title).toBe('使用中 · 还有 7 天到期')
    expect(describeRentalOrderHead(rentalOrder({ status: 'IN_USE' }), new Date('2026-11-26T02:00:00Z')).title).toBe('使用中 · 今天到期')
    expect(describeRentalOrderHead(rentalOrder({ status: 'ENDED' }), NOW).tone).toBe('DONE')
    expect(describeRentalOrderHead(rentalOrder({ status: 'CANCELLED', cancelReason: '设备检修' }), NOW).text).toContain('设备检修')

    const chain = rentalChain('IN_USE')
    expect(chain?.map((step) => [step.label, step.done, step.current])).toEqual([
      ['待报价', true, false], ['待确认', true, false], ['待交接', true, false],
      ['使用中', false, true], ['待归还', false, false], ['已结束', false, false],
    ])
    expect(rentalChain('ENDED')?.every((step) => step.done)).toBe(true)
    expect(rentalChain('CANCELLED')).toBeNull()
  })

  it('shows the agreed fees: rent and fees paid, deposit offline, overtime and damage ¥0', () => {
    expect(rentalFeeRows(rentalOrder()).map((row) => [row.name, row.state, row.amount])).toEqual([
      ['租金', '5 天 × ¥380 × 1 台 · 已支付 11/19', '¥1,900'],
      ['送达与取回', '东营市内', '含'],
      ['保险', '和租金一起支付', '¥50'],
      ['押金', '不在线收取，按合同约定', '线下约定'],
      ['超期和赔付', '按合同线下处理，没有发生', '¥0'],
    ])
    const cancelled = rentalOrder({ status: 'CANCELLED', refund: { refundId: 'f-1', amountMinor: 195_000, status: 'SUCCEEDED' } })
    expect(rentalFeeRows(cancelled)[0].state).toBe('5 天 × ¥380 × 1 台 · 已退款 ¥1,950')
  })

  it('shows records as written and waiting for the customer', () => {
    expect(rentalHandoverView(rentalOrder())).toMatchObject({ tag: '未开始', note: '交接时商家在这里录入记录，你核对后确认' })
    expect(rentalHandoverView(rentalOrder({ handoverRecord: record }))).toMatchObject({ tag: '待你确认', note: null })
    expect(rentalReturnView(rentalOrder({ returnRecord: { ...record, confirmedAt: '2026-11-26T08:00:00Z' } })).tag).toBe('双方已确认')
    expect(rentalReturnView(rentalOrder()).rows.map((row) => row.value)).toEqual(['11/26 前', '商家上门取回', '设备编号、配件数量、外观'])
  })

  it('offers the one action the server allows', () => {
    expect(rentalOrderAction(rentalOrder({ status: 'PENDING_PAYMENT' }))?.kind).toBe('PAY')
    expect(rentalOrderAction(rentalOrder({ status: 'HANDOVER_RECORDED', handoverConfirmable: true }))?.kind).toBe('CONFIRM_HANDOVER')
    expect(rentalOrderAction(rentalOrder({ status: 'RETURN_RECORDED', returnConfirmable: true }))?.kind).toBe('CONFIRM_RETURN')
    // Recorded but not confirmable (for example an older answer): nothing to press.
    expect(rentalOrderAction(rentalOrder({ status: 'HANDOVER_RECORDED' }))).toBeNull()
    expect(rentalOrderAction(rentalOrder({ status: 'IN_USE' }))).toBeNull()
    expect(rentalOrderAction(rentalOrder({ status: 'ENDED' }))?.label).toBe('再次租赁')
    const sheet = rentalConfirmSheet(rentalOrder({ handoverRecord: record }), 'CONFIRM_HANDOVER')
    expect(sheet).toMatchObject({ title: '确认交接', go: '确认交接' })
    expect(sheet.rows.map((row) => row.label)).toEqual(['时间', '记录'])
  })

  it('summarises the contract and the list card', () => {
    expect(rentalContractSummary(rentalOrder()).map((row) => row.value)).toEqual([
      '11/22–11/26 · 5 天', '1 台', '商家送达', '商家上门取回', '东营市 东营港 示例港路 6 号',
    ])
    expect(rentalContractTag('IN_USE')).toEqual({ label: '生效中', tone: 'DONE' })
    expect(rentalContractTag('CLOSED').label).toBe('未生效 · 未付款')
    expect(describeRentalOrderCard(rentalOrder(), NOW)).toMatchObject({
      statusText: '待交接', title: '巡检机器狗 ×1', period: '11/22–11/26 · 共 5 天', amount: '¥1,950', pay: false,
    })
    expect(describeRentalOrderCard(rentalOrder({ status: 'PENDING_PAYMENT' }), NOW).pay).toBe(true)
  })
})
