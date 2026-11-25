import { describe, expect, it } from 'vitest'

import {
  describeServiceOrderLine,
  formatFileSize,
  serviceOrderFileKind,
} from '../application/service-order-format'
import {
  SERVICE_ORDER_STATUS_LABELS,
  describeServiceDeliveryHead,
  describeServiceDeliverySummary,
  describeServiceFulfilment,
  describeServiceOrderCard,
  describeServiceOrderFailure,
  describeServiceOrderHead,
  describeServicePlanCard,
  describeServicePlanPage,
  serviceAcceptRows,
  serviceAcceptedLine,
  serviceArchiveNote,
  serviceDeliveryHistory,
  serviceDeliveryReviewable,
  serviceOrderCancelCopy,
  serviceOrderChain,
  serviceOrderPrimaryAction,
  servicePaymentRows,
  servicePlanBar,
} from '../application/service-order-view'
import type { ServiceOrderStatus } from '../domain/service-order'
import { delivery, orderIn, summary } from './service-order-fixtures'

const ALL: ServiceOrderStatus[] = [
  'AWAITING_PLAN', 'PLAN_PENDING', 'AWAITING_PAYMENT', 'AWAITING_START', 'IN_SERVICE',
  'DELIVERED', 'REWORK', 'COMPLETED', 'CANCELLED', 'TERMINATED',
]

describe('service order status wording', () => {
  it('names every status and heads the order page for each', () => {
    expect(ALL.map((status) => SERVICE_ORDER_STATUS_LABELS[status])).toEqual([
      '待出方案', '待确认方案', '待付款', '待开工', '服务中', '待验收', '整改中', '已完成', '已取消', '已终止',
    ])
    expect(ALL.map((status) => describeServiceOrderHead(orderIn(status)).title)).toEqual([
      '待出方案 · 服务商正在出方案',
      '待确认 · 方案 v2 已出',
      '待付款 · 一次付清 ¥3,860',
      '待开工 · 已付款',
      '服务中',
      '待验收 · 第 1 次交付',
      '整改中 · 第 1 次交付已退回',
      '已完成',
      '已取消',
      '已终止 · 协商终止',
    ])
    expect(describeServiceOrderHead({ ...orderIn('PLAN_PENDING'), plan: null }).title).toBe('待确认 · 方案已出')
    expect(describeServiceOrderHead({ ...orderIn('DELIVERED'), deliveries: [] }).title).toBe('待验收 · 成果已交付')
    expect(describeServiceOrderHead(orderIn('PLAN_PENDING')).text).toBe('服务商 11/23 10:00 出具方案，请核对后确认')
    expect(describeServiceOrderHead(orderIn('DELIVERED')).text).toBe('第 1 次交付 11/26 16:00 已提交，请核对后验收')
    expect(describeServiceOrderHead(orderIn('CANCELLED')).text).toBe('你在开工前取消了订单；没有付款，不涉及费用')
    expect(describeServiceOrderHead(orderIn('TERMINATED')).text).toBe('服务商与你协商终止：设备停产无法维修')
    expect(describeServiceOrderHead(orderIn('CANCELLED')).tone).toBe('MUTED')
    expect(describeServiceOrderHead(orderIn('COMPLETED')).tone).toBe('DONE')
  })

  it('says when the provider cannot take the payment online', () => {
    const order = orderIn('AWAITING_PAYMENT')
    expect(describeServiceOrderHead({ ...order, payment: { ...order.payment, collectable: false } }).text)
      .toBe('服务商暂未开通在线收款，请先联系服务商')
    expect(describeServiceOrderHead({ ...order, payment: { ...order.payment, pendingPayBefore: '2026-11-23T04:15:00Z' } }).text)
      .toBe('请在 11/23 12:15 前付款，超时可重新发起')
  })

  it('walks the seven-step chain; rework stands in for 服务中; cancelled and terminated leave it', () => {
    const current = (status: ServiceOrderStatus) => serviceOrderChain(status)?.find((step) => step.current)?.label ?? null
    expect(ALL.map(current)).toEqual(['待出方案', '待确认', '待付款', '待开工', '服务中', '待验收', '整改中', null, null, null])
    expect(serviceOrderChain('COMPLETED')?.every((step) => step.done)).toBe(true)
    expect(serviceOrderChain('CANCELLED')).toBeNull()
    expect(serviceOrderChain('TERMINATED')).toBeNull()
    expect(serviceOrderChain('AWAITING_START')?.map((step) => step.reached)).toEqual([true, true, true, true, false, false, false])
  })
})

describe('service order page cards', () => {
  it('shows the plan card before and after the plan', () => {
    expect(describeServicePlanCard(orderIn('AWAITING_PLAN'))).toMatchObject({
      title: '服务方案', tag: { label: '待出方案', tone: 'ACTION' }, note: '服务商出方案后在这里核对并确认',
    })
    expect(describeServicePlanCard(orderIn('PLAN_PENDING'))).toMatchObject({
      title: '服务方案 v2',
      tag: { label: '待确认' },
      time: '11/23 10:00',
      rows: [
        { key: 'method', label: '方式', value: '上门检测 + 寄修' },
        { key: 'schedule', label: '安排', value: '11/26 上门检测，约半天' },
        { key: 'terms', label: '交付约定', value: '检测后 48 小时内交付维修记录、更换件保留旧件' },
      ],
    })
    expect(describeServicePlanCard(orderIn('AWAITING_PAYMENT')).tag).toEqual({ label: '已确认', tone: 'DONE' })
  })

  it('has one 一次付清 line and every refund with the trade module status', () => {
    const states = ALL.map((status) => servicePaymentRows(orderIn(status))[0].state)
    expect(states).toEqual([
      '确认方案后支付', '确认方案后支付', '方案已确认，一次付清', '已支付 · 11/23', '已支付 · 11/23',
      '已支付 · 11/23', '已支付 · 11/23', '已支付 · 11/23', '订单已关闭，无需支付', '已支付 · 11/23',
    ])
    expect(servicePaymentRows(orderIn('AWAITING_PAYMENT'))[0]).toMatchObject({ tag: '待支付', amountTone: 'ACCENT', amount: '¥3,860' })

    const paid = orderIn('AWAITING_START')
    const cancelled = orderIn('CANCELLED', {
      payment: {
        ...paid.payment,
        refundedMinor: 386_000,
        refunds: [{ refundId: 'r-1', kind: 'CANCEL', amountMinor: 386_000, reason: '计划有变', status: 'PROCESSING', requestedAt: 'x' }],
      },
    })
    expect(servicePaymentRows(cancelled)).toEqual([
      expect.objectContaining({ key: 'once', state: '已支付 · 11/23' }),
      expect.objectContaining({ key: 'refund-r-1', name: '退款', tag: '取消退款', state: '退款中 ¥3,860 · 计划有变', stateTone: 'ACTION' }),
    ])
    const done = { ...cancelled.payment.refunds[0], status: 'SUCCEEDED' as const }
    expect(servicePaymentRows({ ...cancelled, payment: { ...cancelled.payment, refunds: [done] } })[1])
      .toMatchObject({ state: '已退款 ¥3,860 · 计划有变', stateTone: 'DONE' })
    expect(describeServiceOrderHead(cancelled).text).toBe('你在开工前取消了订单；已支付的款项整笔原路退回')
  })

  it('shows the work and the latest delivery once there is something to show', () => {
    expect(describeServiceFulfilment(orderIn('AWAITING_PAYMENT'))).toBeNull()
    expect(describeServiceFulfilment(orderIn('AWAITING_START'))).toEqual({ state: '待服务商开工', time: '11/23 已付款', done: false })
    expect(describeServiceFulfilment(orderIn('IN_SERVICE'))).toEqual({ state: '服务中', time: '11/26 开工', done: false })
    expect(describeServiceFulfilment(orderIn('COMPLETED'))).toEqual({ state: '已验收', time: '11/27 完成', done: true })
    expect(describeServiceDeliverySummary(orderIn('IN_SERVICE'))).toBeNull()
    expect(describeServiceDeliverySummary(orderIn('DELIVERED'))).toEqual({
      round: 1,
      time: '第 1 次 · 11/26 16:00 交付',
      note: '4 台无人机检测完毕，更换桨叶 2 副',
      files: '2 个文件：维修记录.pdf、现场照片.jpg',
    })
  })

  it('offers the next step from the server flags only', () => {
    expect(ALL.map((status) => serviceOrderPrimaryAction(orderIn(status))?.label ?? null)).toEqual([
      null, '去确认方案', '去付款', null, null, '去验收', null, null, null, null,
    ])
    const order = orderIn('AWAITING_PAYMENT')
    expect(serviceOrderPrimaryAction({ ...order, actions: { ...order.actions, pay: false } })).toBeNull()
  })

  it('tells what cancelling costs: nothing unpaid, a whole refund when paid', () => {
    expect(serviceOrderCancelCopy(orderIn('AWAITING_PLAN')).text).toBe('还没有付款，取消不涉及费用。取消后服务商停止安排本次服务。')
    expect(serviceOrderCancelCopy(orderIn('AWAITING_START')).text)
      .toBe('已支付的 ¥3,860 将整笔原路退回，到账时间以微信支付为准。取消后服务商停止安排本次服务。')
  })
})

describe('plan page (PlanConfirm, one visit)', () => {
  it('lists the quote lines, the arrangement, the terms and the one payment', () => {
    const view = describeServicePlanPage(orderIn('PLAN_PENDING'))
    expect(view).toMatchObject({ version: 2, chip: { label: '待确认' }, updated: '11/23 更新', total: '¥3,860' })
    expect(view.lines.map((line) => [line.name, line.quantity, line.amount])).toEqual([
      ['上门检测', '4 台 × ¥590', '¥2,360'],
      ['更换件', '', '¥1,500'],
    ])
    expect(view.schedule.map((row) => row.label)).toEqual(['范围', '方式', '安排', '地点'])
    expect(view.terms).toEqual(['检测后 48 小时内交付维修记录', '更换件保留旧件'])
    expect(view.payment.state).toBe('确认方案后支付')
    // A version-1 plan is "出具", later ones "更新".
    const first = orderIn('PLAN_PENDING')
    expect(describeServicePlanPage({ ...first, plan: { ...first.plan!, version: 1 } }).updated).toBe('11/23 出具')
  })

  it('waits, asks for the tick, then pays, then says who confirmed', () => {
    expect(servicePlanBar(orderIn('AWAITING_PLAN'))).toEqual({ kind: 'WAITING' })
    expect(servicePlanBar(orderIn('PLAN_PENDING'))).toEqual({ kind: 'CONFIRM', version: 2 })
    expect(servicePlanBar(orderIn('AWAITING_PAYMENT'))).toEqual({
      kind: 'PAY', version: 2, amount: '¥3,860', collectable: true, by: '王女士', at: '11/23 11:00',
    })
    const unpaid = orderIn('AWAITING_PAYMENT')
    expect(servicePlanBar({ ...unpaid, payment: { ...unpaid.payment, collectable: false } })).toMatchObject({ kind: 'PAY', collectable: false })
    expect(servicePlanBar(orderIn('AWAITING_START'))).toEqual({ kind: 'CONFIRMED', version: 2, by: '王女士', at: '11/23 11:00' })
    expect(servicePlanBar(orderIn('CANCELLED'))).toEqual({ kind: 'CLOSED' })
  })
})

describe('delivery page (DeliveryOne)', () => {
  it('heads the delivery, keeps the history newest first with the reasons', () => {
    const second = delivery({ round: 2, deliveredAt: '2026-11-27T08:00:00Z' })
    const first = delivery({ status: 'RETURNED', reviewNote: '缺少检测报告' })
    const order = orderIn('DELIVERED', { deliveries: [first, second] })
    expect(describeServiceDeliveryHead(order, second)).toEqual({
      subtitle: '设备维护需求 · 云翼科技',
      title: '第 2 次交付 · 11/27',
      tag: { label: '待验收', tone: 'ACTION' },
      line: '11/27 16:00 已交付',
    })
    expect(serviceDeliveryHistory(order)).toEqual([
      { round: 2, title: '第 2 次 · 11/27 16:00 交付', tag: { label: '待验收', tone: 'ACTION' }, reason: null, files: '2 个文件' },
      { round: 1, title: '第 1 次 · 11/26 16:00 交付', tag: { label: '已退回', tone: 'MUTED' }, reason: '退回原因：缺少检测报告', files: '2 个文件' },
    ])
    // Only the latest waiting delivery can be accepted or sent back.
    expect(serviceDeliveryReviewable(order, second)).toBe(true)
    expect(serviceDeliveryReviewable(order, first)).toBe(false)
    expect(serviceDeliveryReviewable(orderIn('COMPLETED'), orderIn('COMPLETED').deliveries[0])).toBe(false)
  })

  it('describes the acceptance sheet and the accepted banner', () => {
    const order = orderIn('DELIVERED')
    expect(serviceAcceptRows(order, order.deliveries[0])).toEqual([
      { key: 'content', label: '验收内容', value: '维修记录.pdf、现场照片.jpg' },
      { key: 'provider', label: '服务商', value: '云翼科技' },
      { key: 'customer', label: '验收方', value: '东营港务物流（经办人 王女士）（企业）' },
      { key: 'amount', label: '合同金额', value: '¥3,860 · 已一次付清' },
    ])
    expect(serviceAcceptedLine(orderIn('COMPLETED').deliveries[0])).toBe('11/27 10:00 · 王女士 · 验收记录已留存')
    expect(serviceArchiveNote(order)).toBe('影像存档按交付约定保存 90 天')
    expect(serviceArchiveNote({ ...order, quote: { ...order.quote, archiveDays: 0 } })).toBeNull()
  })

  it('formats files', () => {
    expect(formatFileSize(2_400_000)).toBe('2.3 MB')
    expect(formatFileSize(860_000)).toBe('840 KB')
    expect(serviceOrderFileKind('application/pdf', 'a.pdf')).toBe('DOC')
    expect(serviceOrderFileKind('image/png', 'a.png')).toBe('IMAGE')
    expect(serviceOrderFileKind('video/mp4', 'a.mp4')).toBe('VIDEO')
    expect(serviceOrderFileKind('application/zip', 'a.zip')).toBe('ARCHIVE')
    expect(serviceOrderFileKind('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'a.xlsx')).toBe('SHEET')
    expect(describeServiceOrderLine({ name: '飞行', quantity: 2, unit: null, unitAmountMinor: 10_050, amountMinor: 20_100 }, 0).quantity)
      .toBe('2 × ¥100.50')
  })
})

describe('command failures', () => {
  it('reads the order again when it changed and says why', () => {
    const fail = (status: number, code: string | null) => describeServiceOrderFailure({ status, code, message: 'm' })
    expect(fail(409, 'SERVICE_ORDER_PLAN_CHANGED')).toEqual({ message: '服务商更新了方案，已刷新，请核对最新方案后再确认', reload: true })
    expect(fail(412, 'SERVICE_ORDER_VERSION_CONFLICT').reload).toBe(true)
    expect(fail(412, null).reload).toBe(true)
    expect(fail(409, 'SERVICE_ORDER_PAYMENT_UNAVAILABLE').message).toBe('服务商暂未开通在线收款，请先联系服务商')
    expect(fail(409, 'SERVICE_ORDER_DELIVERY_CHANGED').reload).toBe(true)
    expect(fail(422, 'SERVICE_ORDER_TERMS_NOT_AGREED').reload).toBe(false)
    expect(fail(503, 'SERVICE_ORDER_FILE_STORE_UNAVAILABLE')).toEqual({ message: '文件暂时无法下载，请稍后重试', reload: false })
    expect(fail(0, 'NETWORK_ERROR').message).toBe('网络连接失败，请稍后重试')
    expect(describeServiceOrderFailure(null).reload).toBe(false)
  })
})

describe('我的订单 card of a service order', () => {
  it('says where the order stands and offers its next step', () => {
    expect(describeServiceOrderCard(summary())).toEqual({
      statusText: '待出方案',
      tone: 'ACTION',
      lines: [
        { key: 'customer', text: '企业 · 东营港务物流', tone: 'MUTED' },
        { key: 'state', text: '等待服务商出方案', tone: 'PLAIN' },
      ],
      buttons: [{ kind: 'VIEW', label: '查看订单' }],
    })
    const buttons = (status: ServiceOrderStatus) => describeServiceOrderCard(summary({ status, planVersion: 2 })).buttons.map((button) => button.label)
    expect(buttons('PLAN_PENDING')).toEqual(['查看订单', '去确认方案'])
    expect(buttons('AWAITING_PAYMENT')).toEqual(['查看订单', '去付款'])
    expect(buttons('DELIVERED')).toEqual(['查看订单', '去验收'])
    expect(buttons('COMPLETED')).toEqual(['查看订单'])
    expect(describeServiceOrderCard(summary({ status: 'AWAITING_PAYMENT', planVersion: 2 })).lines[1].text).toBe('方案 v2 已确认 · 待一次付清')
    expect(describeServiceOrderCard(summary({ status: 'CANCELLED', paidAt: 'x' })).lines[1].text).toBe('已取消 · 已付款项整笔原路退回')
  })
})
