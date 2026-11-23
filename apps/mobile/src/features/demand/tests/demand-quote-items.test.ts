import { describe, expect, it, vi } from 'vitest'

import { demandTabCounts } from '../application/demand-list-sources'
import {
  buildDemandCompareRows,
  demandChoiceText,
  demandMakesServiceOrder,
  describeDemandCard,
  describeDemandQuoteItems,
  describeDemandQuoteLines,
  formatArchiveDays,
  formatReportHours,
  resolveDemandFollowUp,
} from '../application/demand-view'
import { createDemandApi, toDemand, toDemandQuote } from '../data/demand-api'
import { demandOf, quoteOf } from './demand-fixtures'

// contracts/openapi-demand.yaml 1.3.0 (S4-3, S4-4): quote lines and comparison items, the service
// order a choice makes, the list counts.
describe('quote lines and comparison items (1.3.0)', () => {
  it('reads the lines and items, blank text as not said', () => {
    const quote = toDemandQuote({
      quoteId: 'q1', version: 2, status: 'ACTIVE', amountMinor: 1_860_000,
      lines: [
        { name: '无人机巡检', quantity: 6, unit: '次', unitAmountMinor: 180_000, amountMinor: 1_080_000 },
        { name: '报告与隐患跟踪', quantity: null, unit: null, unitAmountMinor: null, amountMinor: 120_000 },
      ],
      team: ' 东营本地 ', nightWork: '  ', reportHours: 48, archiveDays: 0, firstVisitDate: '2026-11-02',
    } as never)
    expect(quote).toMatchObject({
      team: '东营本地', nightWork: null, reportHours: 48, archiveDays: 0, firstVisitDate: '2026-11-02',
    })
    expect(describeDemandQuoteLines(quote.lines)).toEqual([
      { key: '0-无人机巡检', name: '无人机巡检', quantity: '6 次 × ¥1,800', amount: '¥10,800' },
      { key: '1-报告与隐患跟踪', name: '报告与隐患跟踪', quantity: '', amount: '¥1,200' },
    ])
    // An older quote has none of them.
    const older = toDemandQuote({ quoteId: 'q2', version: 1, status: 'ACTIVE', amountMinor: 1 } as never)
    expect(older).toMatchObject({ lines: [], team: null, nightWork: null, reportHours: null, archiveDays: null, firstVisitDate: null })
    expect(describeDemandQuoteItems(older)).toEqual([])
  })

  it('formats the items as the board does and never makes one up', () => {
    expect(formatReportHours(48)).toBe('48 小时内')
    expect(formatArchiveDays(90)).toBe('90 天')
    expect(formatArchiveDays(0)).toBe('不含')
    expect(describeDemandQuoteItems(quoteOf({ team: '东营本地', reportHours: 72, firstVisitDate: '2026-11-05' }))).toEqual([
      { label: '服务团队', value: '东营本地' },
      { label: '首次进场', value: '11/05' },
      { label: '报告时效', value: '72 小时内' },
    ])
  })

  it('compares the items a quote fills, dashes the others and greys 不含', () => {
    const quotes = [
      quoteOf({ quoteId: 'a', team: '东营本地', nightWork: '机器狗夜巡', reportHours: 48, archiveDays: 90, firstVisitDate: '2026-11-02' }),
      quoteOf({ quoteId: 'b', team: '东营本地', nightWork: '不含', reportHours: 72, archiveDays: 30, firstVisitDate: '2026-11-05' }),
      quoteOf({ quoteId: 'c', team: null, nightWork: null, reportHours: null, archiveDays: 0, firstVisitDate: null }),
    ]
    const rows = buildDemandCompareRows(quotes)
    expect(rows.map((row) => row.label)).toEqual([
      '总价', '方案版本', '资质', '服务团队', '方式', '工期', '周期', '首次进场', '夜间作业', '报告时效', '交付物', '影像存档', '有效期', '状态',
    ])
    const row = (label: string) => rows.find((item) => item.label === label)!
    expect(row('服务团队').cells).toEqual(['东营本地', '东营本地', '—'])
    expect(row('夜间作业')).toMatchObject({ cells: ['机器狗夜巡', '不含', '—'], muted: [false, true, true] })
    expect(row('报告时效').cells).toEqual(['48 小时内', '72 小时内', '—'])
    expect(row('影像存档')).toMatchObject({ cells: ['90 天', '30 天', '不含'], muted: [false, false, true] })
    expect(row('首次进场').cells).toEqual(['11/02', '11/05', '—'])
    // Without any 1.3.0 items the table is as before.
    expect(buildDemandCompareRows([quoteOf()]).map((item) => item.label))
      .toEqual(['总价', '方案版本', '资质', '方式', '工期', '周期', '交付物', '有效期', '状态'])
  })
})

describe('choosing makes a service order (S4-3)', () => {
  it('reads the service order of a chosen demand', () => {
    const demand = toDemand({ demandId: 'd1', status: 'CHOSEN', type: 'AERIAL', title: '航拍需求', version: 1, serviceOrderId: 'so-1' } as never)
    expect(demand.serviceOrderId).toBe('so-1')
    expect(toDemand({ demandId: 'd2', status: 'CHOSEN', type: 'INSPECTION', title: 't', version: 1 } as never).serviceOrderId).toBeNull()
  })

  it('goes to the order after a choice of a four-type demand, keeps the gate for inspection', () => {
    expect(resolveDemandFollowUp(demandOf({ status: 'CHOSEN', serviceOrderId: 'so-1' }), true)).toEqual({ kind: 'ORDER', serviceOrderId: 'so-1' })
    expect(resolveDemandFollowUp(demandOf({ status: 'CHOSEN', serviceOrderId: 'so-1' }), false).kind).toBe('UNAVAILABLE')
    expect(resolveDemandFollowUp(demandOf({ status: 'CHOSEN', serviceOrderId: null }), true).kind).toBe('UNAVAILABLE')
    expect(demandMakesServiceOrder({ type: 'MAINTENANCE' }, true)).toBe(true)
    expect(demandMakesServiceOrder({ type: 'INSPECTION' }, true)).toBe(false)
  })

  it('says what a choice leads to in the sheet', () => {
    const quote = quoteOf({ amountMinor: 386_000, version: 2 })
    expect(demandChoiceText({ type: 'MAINTENANCE' }, quote, true)).toBe(
      '总价 ¥3,860（方案 v2）。选定后不能再选别家，其余报价会标为未选中。选定后生成服务订单，服务商出方案后由你确认并一次付清。')
    expect(demandChoiceText({ type: 'INSPECTION' }, quote, true)).toBe(
      '总价 ¥3,860（方案 v2）。选定后不能再选别家，其余报价会标为未选中。选定不产生费用，方案确认和付款还没有开通。')
    expect(demandChoiceText({ type: 'AERIAL' }, quote, false)).toContain('方案确认和付款还没有开通')
  })

  it('adds 查看订单 to a chosen demand card with an order where service orders are open', () => {
    const chosen = demandOf({ status: 'CHOSEN', type: 'AERIAL', serviceOrderId: 'so-1' })
    expect(describeDemandCard(chosen, true).buttons.map((button) => [button.action, button.label]))
      .toEqual([['ORDER', '查看订单'], ['QUOTES', '查看报价']])
    expect(describeDemandCard(chosen, false).buttons.map((button) => button.action)).toEqual(['QUOTES'])
    expect(describeDemandCard(demandOf({ status: 'CHOSEN' }), true).buttons.map((button) => button.action)).toEqual(['QUOTES'])
  })
})

describe('需求与报价 tab counts (1.3.0)', () => {
  it('reads the demand counts', async () => {
    const request = vi.fn().mockResolvedValue({ total: 5, draft: 2, open: 1, quoted: 1, chosen: 1, closed: 0 })
    const counts = await createDemandApi(request).counts('tok')
    expect(request).toHaveBeenCalledWith({ url: '/api/v1/demand-counts', method: 'GET', headers: { Authorization: 'Bearer tok' } })
    expect(counts).toEqual({ total: 5, draft: 2, open: 1, quoted: 1, chosen: 1, closed: 0 })
  })

  it('needs every total: demand sheets without drafts, both inquiry lists, all three together', () => {
    expect(demandTabCounts({ total: 5, draft: 2 }, 1, 3)).toEqual({ ALL: 9, DEMAND: 3, INQUIRY: 4, DRAFT: 2 })
    expect(demandTabCounts({ total: 0, draft: 0 }, 0, 0)).toEqual({ ALL: 0, DEMAND: 0, INQUIRY: 0, DRAFT: 0 })
    expect(demandTabCounts(null, 1, 3)).toBeNull()
    expect(demandTabCounts({ total: 5, draft: 2 }, null, 3)).toBeNull()
  })
})
