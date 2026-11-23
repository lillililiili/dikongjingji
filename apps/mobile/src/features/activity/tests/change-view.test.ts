import { describe, expect, it } from 'vitest'

import { describeChange, describeChangeLine, refundPhase } from '../application/change-view'
import type { ChangeDetail } from '../domain/enrollment'
import { s53Change } from './result-change-fixtures'

function detail(change = s53Change()): ChangeDetail {
  return {
    change,
    enrollmentId: 'e1',
    activityTitle: '青少年无人机编程挑战赛（东营站）',
    coverImage: null,
    organizerName: '东营低空与机器人体验馆',
    tickets: [
      { ticketId: 't1', participantName: '王小禾', relation: 'CHILD', age: 11, issued: false, ticketCode: null, status: 'CANCELLED' },
      { ticketId: 't2', participantName: '王小川', relation: 'CHILD', age: 9, issued: false, ticketCode: null, status: 'CANCELLED' },
    ],
  }
}

describe('退改详情 of a refund', () => {
  it('shows a refund on its way with nothing waiting for the organiser', () => {
    const view = describeChange(detail())
    expect(view.statusTitle).toBe('退款中')
    expect(view.statusSub).toBe('2 个名额已释放，原凭证已作废')
    expect(view.steps.map((step) => [step.title, step.kind])).toEqual([
      ['提交申请', 'DONE'], ['释放名额', 'DONE'], ['退款原路退回', 'CURRENT'],
    ])
    expect(view.steps.map((step) => step.title)).not.toContain('主办方确认')
    expect(view.info[0]).toEqual({ label: '退款金额', value: '¥240', strong: 'AMOUNT' })
    expect(view.apply.find((row) => row.label === '参与人')?.value).toBe('王小禾、王小川')
    expect(view.apply.find((row) => row.label === '申请原因')?.value).toBe('时间冲突')
    expect(view.refreshable).toBe(true)
  })

  it('treats an accepted request as still on its way', () => {
    expect(refundPhase(s53Change({ refundStatus: 'REQUESTED' }))).toBe('PENDING')
  })

  it('shows when the money arrived', () => {
    const view = describeChange(detail(s53Change({ refundStatus: 'SUCCEEDED', refundSucceededAt: '2026-11-04T01:10:00Z' })))
    expect(view.statusTitle).toBe('已退款')
    expect(view.statusSub).toBe('¥240 已原路退回，凭证已作废')
    expect(view.info.find((row) => row.label === '到账时间')?.value).toBe('11/04 09:10')
    expect(view.steps[2]).toEqual({ title: '退款原路退回', sub: '11/04 09:10 · 已到账 微信支付', kind: 'DONE' })
    expect(view.refreshable).toBe(false)
  })

  it('sends a closed or abnormal refund to customer service', () => {
    const view = describeChange(detail(s53Change({ refundStatus: 'ABNORMAL' })))
    expect(view.statusTitle).toBe('退款异常')
    expect(view.steps[2].kind).toBe('END')
  })

  it('says a free cancellation released the seats and nothing else', () => {
    const view = describeChange(detail(s53Change({ refundAmountMinor: null, refundStatus: null })))
    expect(view.statusTitle).toBe('已取消')
    expect(view.steps).toHaveLength(2)
    expect(view.apply[0].value).toBe('取消报名')
  })
})

describe('退改详情 of a reschedule', () => {
  const moved = s53Change({
    kind: 'RESCHEDULE',
    refundAmountMinor: null,
    refundStatus: null,
    toSession: { sessionId: 's-next', label: '上午', startsAt: '2026-11-22T01:00:00Z' },
  })

  it('took effect at once and needs no top-up', () => {
    const view = describeChange(detail(moved))
    expect(view.statusTitle).toBe('已改期')
    expect(view.statusSub).toBe('名额已转入 11/22 上午 09:00，原凭证已作废')
    expect(view.info).toEqual([
      { label: '原场次', value: '11/15 上午 09:00' },
      { label: '新场次', value: '11/22 上午 09:00', strong: 'BOLD' },
      { label: '费用变化', value: '无需补差价' },
    ])
    expect(view.steps.every((step) => step.kind === 'DONE')).toBe(true)
  })

  it('reads as one line in a list', () => {
    expect(describeChangeLine(moved)).toEqual({ title: '已改期', sub: '11/15 上午 09:00 → 11/22 上午 09:00' })
    expect(describeChangeLine(s53Change())).toEqual({ title: '退款中', sub: '2 人 · ¥240 原路退回' })
  })
})
