import { describe, expect, it } from 'vitest'

import { buildQuoteRequest, initialQuoteForm, quoteAmountMinor, type ProviderDemand, type QuoteForm } from './demand-quotes-api'
import {
  buildLines,
  extrasRows,
  isPerUnit,
  lineAmountMinor,
  lineFormsFrom,
  lineQuantityLabel,
  linesTotalMinor,
  newLineForm,
  type LineForm,
} from './quote-lines'

const NOW = new Date('2026-11-23T02:00:00Z')

function line(overrides: Partial<LineForm> = {}): LineForm {
  return { ...newLineForm(), ...overrides }
}

function form(overrides: Partial<QuoteForm> = {}): QuoteForm {
  return {
    amountYuan: '',
    method: '无人机巡检',
    schedule: '确认后 3 天内进场',
    durationDays: 5,
    deliverables: '巡检报告',
    validUntil: new Date(NOW.getTime() + 7 * 86_400_000),
    lines: [],
    team: '',
    nightWork: '',
    reportHours: null,
    archiveDays: null,
    firstVisitDate: null,
    ...overrides,
  }
}

describe('报价明细', () => {
  it('按次计的行：金额 = 数量 × 单价（元按字符串换成分，不经过浮点）', () => {
    const perUnit = line({ name: '无人机巡检', quantity: '6', unit: '次', unitYuan: '1800' })
    expect(isPerUnit(perUnit)).toBe(true)
    expect(lineAmountMinor(perUnit)).toBe(1_080_000)
    expect(lineAmountMinor(line({ quantity: '3', unit: '天', unitYuan: '0.1' }))).toBe(30)
    expect(lineAmountMinor(line({ quantity: '3', unit: '天', unitYuan: '19.99' }))).toBe(5997)
  })

  it('只填金额的行；合计；有一行算不出来时合计为 null', () => {
    const fixed = line({ name: '报告与隐患跟踪', amountYuan: '1200' })
    expect(isPerUnit(fixed)).toBe(false)
    expect(lineAmountMinor(fixed)).toBe(120_000)
    const perUnit = line({ name: '无人机巡检', quantity: '6', unit: '次', unitYuan: '1800' })
    expect(linesTotalMinor([perUnit, fixed])).toBe(1_200_000)
    expect(linesTotalMinor([])).toBe(0)
    expect(linesTotalMinor([perUnit, line({ amountYuan: '12.345' })])).toBeNull()
  })

  it.each([
    [line({ name: '' , amountYuan: '10' }), '要写名称'],
    [line({ name: '名'.repeat(21), amountYuan: '10' }), '最多 20'],
    [line({ name: '巡检', quantity: '6', unitYuan: '1800' }), '要么都填'],
    [line({ name: '巡检', quantity: '0', unit: '次', unitYuan: '1800' }), '1–999'],
    [line({ name: '巡检', quantity: '1000', unit: '次', unitYuan: '1800' }), '1–999'],
    [line({ name: '巡检', quantity: '2.5', unit: '次', unitYuan: '1800' }), '1–999'],
    [line({ name: '巡检', quantity: '2', unit: '架次单位', unitYuan: '1' }), ''],
    [line({ name: '巡检', quantity: '2', unit: '超过四字了', unitYuan: '1' }), '单位最多 4'],
    [line({ name: '巡检', quantity: '2', unit: '次', unitYuan: '0' }), '单价要大于 0'],
    [line({ name: '报告', amountYuan: '0' }), '金额要大于 0'],
    [line({ name: '报告', amountYuan: '' }), '金额要大于 0'],
  ])('一行不合规时给出第一条错误：%#', (item, message) => {
    const built = buildLines([item])
    if (message === '') {
      expect(built.ok).toBe(true)
      return
    }
    expect(built.ok).toBe(false)
    if (!built.ok) expect(built.message).toContain(message)
  })

  it('最多 10 行；请求里的行：按次计带数量单位单价，只填金额的三项为 null', () => {
    expect(buildLines(Array.from({ length: 11 }, () => line({ name: 'x', amountYuan: '1' }))).ok).toBe(false)
    const built = buildLines([
      line({ name: ' 无人机巡检 ', quantity: '6', unit: '次', unitYuan: '1800' }),
      line({ name: '报告与隐患跟踪', amountYuan: '1200.5' }),
    ])
    expect(built).toEqual({
      ok: true,
      lines: [
        { name: '无人机巡检', quantity: 6, unit: '次', unitAmountMinor: 180_000, amountMinor: 1_080_000 },
        { name: '报告与隐患跟踪', quantity: null, unit: null, unitAmountMinor: null, amountMinor: 120_050 },
      ],
    })
  })

  it('改价时从报价明细预填（分 → 元），显示“数量 单位 × 单价”', () => {
    const lines = [
      { name: '无人机巡检', quantity: 6, unit: '次', unitAmountMinor: 180_000, amountMinor: 1_080_000 },
      { name: '报告', quantity: null, unit: null, unitAmountMinor: null, amountMinor: 120_000 },
    ]
    expect(lineFormsFrom(lines).map(({ key: _key, ...rest }) => rest)).toEqual([
      { name: '无人机巡检', quantity: '6', unit: '次', unitYuan: '1800.00', amountYuan: '10800.00' },
      { name: '报告', quantity: '', unit: '', unitYuan: '', amountYuan: '1200.00' },
    ])
    expect(lineQuantityLabel(lines[0]!)).toBe('6 次 × ¥1,800.00')
    expect(lineQuantityLabel(lines[1]!)).toBe('')
  })
})

describe('报价总价按明细自动合计', () => {
  it('有明细时总价 = 明细合计，填的总价不用；没有明细时用填的总价', () => {
    const lines = [
      line({ name: '无人机巡检', quantity: '6', unit: '次', unitYuan: '1800' }),
      line({ name: '报告', amountYuan: '1200' }),
    ]
    expect(quoteAmountMinor({ amountYuan: '99', lines })).toBe(1_200_000)
    expect(quoteAmountMinor({ amountYuan: '99', lines: [] })).toBe(9900)

    const built = buildQuoteRequest(form({ amountYuan: '99', lines }), NOW)
    expect(built.ok).toBe(true)
    if (built.ok) {
      expect(built.request.amountMinor).toBe(1_200_000)
      expect(built.request.amountMinor).toBe(built.request.lines.reduce((sum, item) => sum + item.amountMinor, 0))
    }
  })

  it('明细有错时不提交；合计超过上限时不提交', () => {
    const bad = buildQuoteRequest(form({ lines: [line({ name: '巡检', quantity: '2', unitYuan: '1' })] }), NOW)
    expect(bad.ok).toBe(false)
    const huge = buildQuoteRequest(form({ lines: [
      line({ name: 'a', amountYuan: '60000000' }), line({ name: 'b', amountYuan: '60000000' }),
    ] }), NOW)
    expect(huge.ok).toBe(false)
    if (!huge.ok) expect(huge.message).toContain('上限')
  })
})

describe('对比项', () => {
  it('不填的是 null；填了的按契约检查', () => {
    const empty = buildQuoteRequest(form({ amountYuan: '100' }), NOW)
    expect(empty.ok && empty.request).toMatchObject({
      lines: [], team: null, nightWork: null, reportHours: null, archiveDays: null, firstVisitDate: null,
    })
    const full = buildQuoteRequest(form({
      amountYuan: '100', team: ' 东营本地 ', nightWork: '机器狗夜巡', reportHours: 24, archiveDays: 0,
      firstVisitDate: '2026-11-28',
    }), NOW)
    expect(full.ok && full.request).toMatchObject({
      team: '东营本地', nightWork: '机器狗夜巡', reportHours: 24, archiveDays: 0, firstVisitDate: '2026-11-28',
    })
    for (const [overrides, message] of [
      [{ team: '团'.repeat(21) }, '服务团队最多 20'],
      [{ nightWork: '夜'.repeat(21) }, '夜间作业最多 20'],
      [{ reportHours: 0 }, '报告时效'],
      [{ reportHours: 721 }, '报告时效'],
      [{ archiveDays: -1 }, '影像存档'],
      [{ archiveDays: 3651 }, '影像存档'],
    ] as Array<[Partial<QuoteForm>, string]>) {
      const built = buildQuoteRequest(form({ amountYuan: '100', ...overrides }), NOW)
      expect(built.ok, message).toBe(false)
      if (!built.ok) expect(built.message).toContain(message)
    }
  })

  it('显示：没填的不显示，影像存档 0 天显示“不含”', () => {
    expect(extrasRows({ team: null, nightWork: null, reportHours: null, archiveDays: null, firstVisitDate: null }))
      .toEqual([])
    expect(extrasRows({ team: '东营本地', nightWork: '不含', reportHours: 48, archiveDays: 0, firstVisitDate: '2026-11-28' }))
      .toEqual([
        { label: '服务团队', value: '东营本地' },
        { label: '夜间作业', value: '不含' },
        { label: '报告时效', value: '作业后 48 小时内' },
        { label: '影像存档', value: '不含' },
        { label: '首次进场', value: '2026-11-28' },
      ])
    expect(extrasRows({ team: null, nightWork: null, reportHours: null, archiveDays: 180, firstVisitDate: null }))
      .toEqual([{ label: '影像存档', value: '180 天' }])
  })

  it('改价时对比项和明细用现在的报价预填', () => {
    const demand = {
      demandId: 'd', status: 'QUOTED', type: 'AERIAL', title: '航拍需求', cityCode: '370500', cityName: '东营市',
      address: 'x', scope: 'y', dueDate: null, budgetMinMinor: null, budgetMaxMinor: null, deliverables: [], note: null,
      invited: false, submittedAt: '', quoteDeadline: '', quotingOpen: true,
      myQuote: {
        quoteId: 'q', version: 1, status: 'ACTIVE', amountMinor: 120_000, method: 'm', schedule: 's', durationDays: 1,
        deliverables: 'd', validUntil: '', submittedAt: '', revisedAt: '',
        lines: [{ name: '报告', quantity: null, unit: null, unitAmountMinor: null, amountMinor: 120_000 }],
        team: '东营本地', nightWork: null, reportHours: 24, archiveDays: 0, firstVisitDate: '2026-11-28',
      },
    } satisfies ProviderDemand
    const initial = initialQuoteForm(demand, NOW)
    expect(initial).toMatchObject({ team: '东营本地', nightWork: '', reportHours: 24, archiveDays: 0,
      firstVisitDate: '2026-11-28' })
    expect(initial.lines.map((item) => item.amountYuan)).toEqual(['1200.00'])
  })
})
