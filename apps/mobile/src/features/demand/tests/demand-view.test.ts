import { describe, expect, it } from 'vitest'

import {
  DEMAND_SORT_LABELS,
  buildDemandCompareRows,
  describeDemandCard,
  describeDemandChoiceFailure,
  describeDemandFacts,
  describeDemandLine,
  describeDemandPublisher,
  describeDemandQuote,
  describeDemandQuoteSummary,
  formatDemandAmount,
  resolveDemandFollowUp,
} from '../application/demand-view'
import { demandOf, quoteOf } from './demand-fixtures'

describe('demand list cards', () => {
  it('a draft says what is missing and offers 删除 and 继续填写', () => {
    const card = describeDemandCard(demandOf({
      status: 'DRAFT', address: null, scope: null, editable: true, deletable: true, cancellable: false,
      submittedAt: null, quoteDeadline: null, quotingOpen: false, updatedAt: '2026-11-18T03:00:00Z',
    }))
    expect(card).toMatchObject({ kind: '草稿', statusText: '未提交', tone: 'DRAFT', open: 'EDIT', line: '11/18 保存' })
    expect(card.meta).toBe('巡检 · 还差地点、作业范围')
    expect(card.buttons.map((button) => button.label)).toEqual(['删除', '继续填写'])
  })

  it('an open demand shows matching or invitations and the deadline in Beijing time', () => {
    expect(describeDemandLine(demandOf())).toBe('平台匹配中 · 报价截止 11/22 10:00')
    expect(describeDemandLine(demandOf({
      quoteScope: 'INVITE',
      invitedProviders: [{ providerId: 'p1', name: '云翼科技', certificationLabel: null, description: null, qualificationReviewed: false }],
    }))).toBe('已邀请 1 家服务商 · 报价截止 11/22 10:00')
    const card = describeDemandCard(demandOf())
    expect(card.meta).toBe('东营经济技术开发区 · 约 1.2 平方公里')
    expect(card.buttons.map((button) => button.action)).toEqual(['CANCEL', 'VIEW'])
    expect(describeDemandCard(demandOf({ cancellable: false })).buttons.map((button) => button.action)).toEqual(['VIEW'])
  })

  it('a quoted demand counts quotes and leads to the comparison', () => {
    const card = describeDemandCard(demandOf({ status: 'QUOTED', quoteCount: 2 }))
    expect(card).toMatchObject({ statusText: '报价中', tone: 'QUOTING', open: 'QUOTES', line: '已收到 2 份报价 · 报价截止 11/22 10:00' })
    expect(card.buttons).toEqual([
      { action: 'CANCEL', label: '取消需求', primary: false },
      { action: 'QUOTES', label: '比选报价', primary: true },
    ])
    expect(describeDemandLine(demandOf({ status: 'QUOTED', quoteCount: 1, quotingOpen: false }))).toBe('已收到 1 份报价 · 报价已截止')
  })

  it('a chosen demand names the provider, the version and the amount', () => {
    const card = describeDemandCard(demandOf({
      status: 'CHOSEN', cancellable: false,
      chosen: { quoteId: 'q1', quoteVersion: 2, providerName: '鹰眼智能巡检', amountMinor: 1_860_000, chosenAt: '2026-11-20T00:00:00Z' },
    }))
    expect(card.line).toBe('已选定 鹰眼智能巡检 · 方案 v2 · ¥18,600')
    expect(card.tone).toBe('CHOSEN')
    expect(card.buttons.map((button) => button.label)).toEqual(['查看报价'])
  })

  it('a closed demand says why and can be published again', () => {
    expect(describeDemandLine(demandOf({ status: 'CLOSED', closeReason: 'NO_QUOTES' }))).toBe('报价期内没有服务商报价')
    const card = describeDemandCard(demandOf({ status: 'CLOSED', closeReason: 'CANCELLED', cancellable: false }))
    expect(card.line).toBe('你已取消这份需求')
    expect(card.buttons.map((button) => button.action)).toEqual(['VIEW', 'REPUBLISH'])
  })

  it('names the publisher as the board does', () => {
    expect(describeDemandPublisher({ kind: 'ORGANIZATION', organizationName: '东营示例物流公司' })).toBe('企业 · 东营示例物流公司')
    expect(describeDemandPublisher({ kind: 'PERSONAL', organizationName: null })).toBe('个人')
  })
})

describe('quotes and comparison', () => {
  it('labels the three sorts', () => {
    expect(DEMAND_SORT_LABELS).toEqual({ ALL: '综合', PRICE: '总价', DURATION: '交付周期' })
  })

  it('shows a quote as the board rows: 方式、工期、周期、交付物、有效期', () => {
    const view = describeDemandQuote(quoteOf())
    expect(view.price).toBe('¥18,600')
    expect(view.statusTag).toBeNull()
    expect(view.rows).toEqual([
      { label: '方式', value: '无人机 + 机器狗' },
      { label: '工期', value: '共 6 次 · 11/25 首次进场' },
      { label: '周期', value: '90 天' },
      { label: '交付物', value: '巡检报告、隐患清单' },
      { label: '有效期', value: '至 11/26 18:00' },
    ])
    expect(describeDemandQuote(quoteOf({ status: 'NOT_CHOSEN', choosable: false })).statusTag).toBe('未选中')
    expect(describeDemandQuote(quoteOf({ status: 'CHOSEN', choosable: false })).statusTag).toBe('已选中')
    // R6-4: beside the name, 资质已审核 only when the server says so; otherwise the provider's own label.
    expect(view.credential).toBe('企业认证')
    expect(describeDemandQuote(quoteOf({
      provider: { providerId: 'p1', name: '云翼科技', certificationLabel: '企业认证', qualificationReviewed: true },
    })).credential).toBe('资质已审核')
    expect(describeDemandQuote(quoteOf({
      provider: { providerId: 'p1', name: '云翼科技', certificationLabel: null, qualificationReviewed: false },
    })).credential).toBeNull()
  })

  it('compares only what the quotes give, one cell per quote', () => {
    const rows = buildDemandCompareRows([
      quoteOf(),
      quoteOf({ quoteId: 'q2', provider: { providerId: 'p2', name: '黄河口飞服', certificationLabel: null, qualificationReviewed: true }, version: 1, amountMinor: 1_520_000, status: 'EXPIRED', choosable: false }),
    ])
    expect(rows.map((row) => row.label)).toEqual(['总价', '方案版本', '资质', '方式', '工期', '周期', '交付物', '有效期', '状态'])
    expect(rows[0]).toEqual({ label: '总价', cells: ['¥18,600', '¥15,200'], price: true })
    // R6-4: a reviewed provider reads 已审核 (PlanCompare board); the others keep their own label.
    expect(rows[2].cells).toEqual(['企业认证', '已审核'])
    expect(rows[8].cells).toEqual(['可选择', '已过有效期'])
  })

  it('summarises the demand above the quotes', () => {
    expect(describeDemandQuoteSummary(demandOf({ status: 'QUOTED' }), 3)).toEqual({ count: '3 家已报价', deadline: '报价截止 11/22 10:00' })
    expect(describeDemandQuoteSummary(demandOf(), 0).count).toBe('还没有报价')
    expect(describeDemandQuoteSummary(demandOf({ status: 'CHOSEN' }), 1).deadline).toBe('已选定')
  })

  it('reads a stale choice again and says why', () => {
    expect(describeDemandChoiceFailure({ status: 412, code: 'DEMAND_VERSION_CONFLICT', message: '' }))
      .toEqual({ message: '需求有更新，已刷新，请看过最新报价后再选', reload: true })
    expect(describeDemandChoiceFailure({ status: 409, code: 'DEMAND_QUOTE_CHANGED', message: '' }).reload).toBe(true)
    expect(describeDemandChoiceFailure({ status: 409, code: 'DEMAND_QUOTE_EXPIRED', message: '' }).message).toBe('这份报价已过有效期，不能再选')
    expect(describeDemandChoiceFailure({ status: 0, code: 'NETWORK_ERROR', message: '' }).reload).toBe(false)
  })

  it('keeps plan confirmation and payment behind a closed gate after a choice', () => {
    expect(resolveDemandFollowUp()).toEqual({
      kind: 'UNAVAILABLE',
      title: '方案确认和付款还没有开通',
      text: '选定报价不产生费用。方案确认、合同和付款开通后，从这里继续。',
    })
  })

  it('formats amounts in yuan', () => {
    expect(formatDemandAmount(1_860_000)).toBe('¥18,600')
    expect(formatDemandAmount(12_345)).toBe('¥123.45')
  })
})

describe('a submitted demand read-only', () => {
  it('lists what was sent', () => {
    const facts = describeDemandFacts(demandOf({ type: 'MAINTENANCE', scope: '巡检机器狗 2 台' }), '11/30 前')
    expect(facts).toEqual([
      { label: '服务类型', value: '设备维护' },
      { label: '服务地点', value: '东营市 · 东营经济技术开发区' },
      { label: '设备情况', value: '巡检机器狗 2 台' },
      { label: '期望完成', value: '11/30 前' },
      { label: '预算区间', value: '1 万–2 万元' },
      { label: '交付物', value: '巡检报告、正射影像' },
      { label: '报价范围', value: '平台匹配' },
    ])
  })
})
