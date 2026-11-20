import { describe, expect, it } from 'vitest'

import { describeMallInquiryCard } from '../application/inquiry-card'
import {
  clampInquiryQuantity,
  createMallInquiryDraft,
  createMallInquiryKeyHolder,
  describeMallInquiryBuyer,
  describeMallInquirySubmitFailure,
  formatExpectedDate,
  inquiryOfferNote,
  keepAskableOffers,
  mallInquiryDraftProblem,
  toMallInquiryInput,
  toggleInquiryOffer,
} from '../application/inquiry-form'
import {
  describeChoiceConfirm,
  describeChoiceFailure,
  describeInquiryBar,
  describeInquiryMerchants,
  describeInquirySummary,
  mallInquiryCloseConfirm,
} from '../application/inquiry-view'
import { classifySubmitFailure } from '../../trade/application/submit-order'
import { mallInquiryOf, offerOf, quoteOf } from './inquiry-fixtures'

const NOW = new Date('2026-11-19T04:00:00Z')

const robot = offerOf()
const agent = offerOf({ offerId: 'o-agent', merchant: { organizationId: 'm-5', name: '示例行业装备代理', kind: 'AUTHORIZED_DEALER' } })
const maker = offerOf({ offerId: 'o-maker', merchant: { organizationId: 'm-4', name: '示例无人机厂家', kind: 'FACTORY_DIRECT' }, canInquire: false })

describe('inquiry form', () => {
  it('starts with every merchant that can be asked ticked, at most three', () => {
    expect(createMallInquiryDraft([maker, robot, agent]).offerIds).toEqual(['o-robot', 'o-agent'])
    const many = ['a', 'b', 'c', 'd'].map((id) => offerOf({ offerId: id }))
    expect(createMallInquiryDraft(many).offerIds).toEqual(['a', 'b', 'c'])
  })

  it('ticks and unticks merchants, refusing one without online payment and a fourth one', () => {
    const draft = createMallInquiryDraft([robot, agent])
    const unticked = toggleInquiryOffer(draft, robot)
    expect(unticked).toEqual({ kind: 'CHANGED', draft: { ...draft, offerIds: ['o-agent'] } })
    expect(toggleInquiryOffer(draft, maker)).toEqual({ kind: 'REFUSED', message: '这家商家暂未开通在线支付，不能发询价' })
    const full = { ...draft, offerIds: ['a', 'b', 'c'] }
    expect(toggleInquiryOffer(full, robot)).toEqual({ kind: 'REFUSED', message: '最多发给 3 家商家' })
    expect(keepAskableOffers({ ...draft, offerIds: ['o-robot', 'gone'] }, [robot]).offerIds).toEqual(['o-robot'])
  })

  it('checks the requirement, date and merchants in page order', () => {
    const draft = createMallInquiryDraft([robot])
    expect(mallInquiryDraftProblem(draft, NOW)).toBe('请填写配置和用途')
    expect(mallInquiryDraftProblem({ ...draft, requirement: 'x'.repeat(201) }, NOW)).toBe('配置和用途不超过 200 字')
    expect(mallInquiryDraftProblem({ ...draft, requirement: '测绘版', expectedDate: '2026-11-19' }, NOW)).toBe('期望交付日期最早是明天')
    expect(mallInquiryDraftProblem({ ...draft, requirement: '测绘版', offerIds: [] }, NOW)).toBe('请至少选择一家商家')
    expect(mallInquiryDraftProblem({ ...draft, requirement: '测绘版', expectedDate: '2026-11-20' }, NOW)).toBeNull()
    expect(clampInquiryQuantity(120)).toBe(99)
    expect(clampInquiryQuantity(0)).toBe(1)
  })

  it('keeps one idempotency key per distinct inquiry', () => {
    let next = 0
    const holder = createMallInquiryKeyHolder(() => `k${++next}`)
    const input = toMallInquiryInput('p-1', '370500', { requirement: ' 测绘版 ', quantity: 2, expectedDate: null, offerIds: ['b', 'a'] })
    expect(input.requirement).toBe('测绘版')
    expect(holder.keyFor(input)).toBe('k1')
    expect(holder.keyFor({ ...input, offerIds: ['a', 'b'] })).toBe('k1')
    expect(holder.keyFor({ ...input, quantity: 3 })).toBe('k2')
  })

  it('explains submit failures', () => {
    expect(describeMallInquirySubmitFailure({ status: 409, code: 'MALL_INQUIRY_MERCHANT_UNAVAILABLE', message: '' }).kind).toBe('MERCHANTS')
    expect(describeMallInquirySubmitFailure({ status: 409, code: 'MALL_INQUIRY_OFFER_UNAVAILABLE', message: '' }).kind).toBe('MERCHANTS')
    expect(describeMallInquirySubmitFailure({ status: 401, code: null, message: '' })).toEqual({ kind: 'UNAUTHORIZED' })
    expect(describeMallInquirySubmitFailure({ status: 404, code: 'COMMON_NOT_FOUND', message: '' }).kind).toBe('BLOCKED')
    expect(describeMallInquirySubmitFailure(null).kind).toBe('RETRY')
  })

  it('describes the buyer, the expected date and a merchant row', () => {
    expect(describeMallInquiryBuyer({ kind: 'ORGANIZATION', organizationName: '东营示例物流公司', contactName: '王女士' }))
      .toEqual({ text: '企业 · 东营示例物流公司', badge: '企业 · 东营示例物流公司' })
    expect(describeMallInquiryBuyer({ kind: 'PERSONAL', organizationName: null, contactName: '李先生' }))
      .toEqual({ text: '个人 · 李先生', badge: '个人' })
    expect(formatExpectedDate('2026-12-15')).toBe('12 月 15 日前')
    expect(formatExpectedDate(null)).toBe('不限')
    expect(inquiryOfferNote(maker, '厂家直营')).toBe('暂未开通在线支付')
    expect(inquiryOfferNote(robot, '厂家直营')).toBe('厂家直营 · 东营交付')
  })
})

describe('quotes page', () => {
  it('summarises the inquiry: spec, delivery, count and deadline', () => {
    const summary = describeInquirySummary(mallInquiryOf(), '东营市')
    expect(summary).toMatchObject({
      title: '行业测绘无人机',
      spec: '测绘版 · RTK · 五镜头倾斜相机 · 2 台',
      delivery: '交付东营市 · 期望 12 月 15 日前',
      statusText: '报价中',
      count: 2,
      line: '已收到 2 份报价，截止 11/22 10:00',
    })
    expect(describeInquirySummary(mallInquiryOf({ status: 'CLOSED', closeReason: 'NO_QUOTES', quotingOpen: false }), null))
      .toMatchObject({ line: '72 小时内没有商家报价，询价已关闭', tone: 'OFF', delivery: '期望 12 月 15 日前' })
  })

  it('shows quotes in the server order, then waiting and declined merchants', () => {
    const inquiry = mallInquiryOf({
      merchants: [
        ...mallInquiryOf().merchants,
        { offerId: 'o-third', merchant: { organizationId: 'm-9', name: '第三家', kind: 'LOCAL_STORE' }, status: 'INVITED', declineReason: null },
        { offerId: 'o-no', merchant: { organizationId: 'm-8', name: '拒绝的', kind: 'LOCAL_STORE' }, status: 'DECLINED', declineReason: '暂无现货' },
      ],
    })
    const quotes = [
      quoteOf({ quoteId: 'q-agent', offerId: 'o-agent', totalMinor: 12_900_000, unitAmountMinor: 6_450_000, leadDays: 12, version: 2,
        merchant: { organizationId: 'm-5', name: '示例行业装备代理', kind: 'AUTHORIZED_DEALER', qualificationReviewed: false } }),
      quoteOf(),
    ]
    const cards = describeInquiryMerchants(inquiry, quotes)
    expect(cards.map((card) => card.key)).toEqual(['q-agent', 'q-robot', 'o-third', 'o-no'])
    const [cheap, fast, waiting, declined] = cards
    expect(cheap).toMatchObject({ kind: 'QUOTE', badge: { text: '报价最低' }, unit: '¥64,500', lead: '12 天', versionText: '第 2 版报价', total: '¥129,000', totalLabel: '合计（2 台）', buttonText: '选择此报价' })
    expect(fast).toMatchObject({ kind: 'QUOTE', badge: { text: '交期最短' }, validUntil: '11/22', includes: '含 2 名飞手培训' })
    expect(waiting).toMatchObject({ kind: 'WAITING', badge: { text: '待报价' }, line: '商家还没有报价，截止 11/22 10:00' })
    expect(declined).toMatchObject({ kind: 'DECLINED', badge: { text: '已拒绝' }, line: '商家已拒绝：暂无现货' })
    // R6-4: the green 资质已审核 tag follows each quoting merchant's own field.
    expect(cheap).toMatchObject({ name: '示例行业装备代理', qualificationReviewed: false })
    expect(fast).toMatchObject({ name: '示例机器人商家', qualificationReviewed: true })
  })

  it('marks chosen, expired and lapsed quotes and their buttons', () => {
    const cards = describeInquiryMerchants(mallInquiryOf(), [
      quoteOf({ status: 'CHOSEN', choosable: false }),
      quoteOf({ quoteId: 'q-2', offerId: 'o-agent', status: 'LAPSED', choosable: false }),
      quoteOf({ quoteId: 'q-3', offerId: 'o-x', status: 'EXPIRED', choosable: false }),
    ])
    expect(cards.map((card) => (card.kind === 'QUOTE' ? [card.badge?.text, card.buttonText, card.chosen] : null))).toEqual([
      ['已选定', '已选择', true],
      ['已失效', '已失效', false],
      ['已过期', '已过期', false],
    ])
  })

  it('decides the bottom bar from the chosen quote, its order and its validity', () => {
    const chosen = {
      quoteId: 'q-robot', quoteVersion: 1, offerId: 'o-robot',
      merchant: { organizationId: 'm-1', name: '示例机器人商家', kind: 'FACTORY_DIRECT' as const },
      unitAmountMinor: 6_800_000, quantity: 2, totalMinor: 13_600_000,
      validUntil: '2026-11-22T10:00:00Z', chosenAt: '2026-11-19T04:00:00Z', orderId: null,
    }
    const picked = mallInquiryOf({ status: 'CHOSEN', chosen, closable: false })
    expect(describeInquiryBar(picked, [], false, NOW)).toEqual({ kind: 'ORDER', merchant: '示例机器人商家', total: '¥136,000' })
    const ordered = mallInquiryOf({ status: 'CHOSEN', chosen: { ...chosen, orderId: 't-1' } })
    expect(describeInquiryBar(ordered, [], true, NOW)).toMatchObject({ kind: 'VIEW_ORDER', orderId: 't-1' })
    // The order closed unpaid: the quote can be ordered again while valid.
    expect(describeInquiryBar(ordered, [], false, NOW).kind).toBe('ORDER')
    expect(describeInquiryBar(picked, [], false, new Date('2026-11-23T00:00:00Z')).kind).toBe('EXPIRED')
    expect(describeInquiryBar(mallInquiryOf(), [quoteOf()], false, NOW)).toEqual({ kind: 'PICK' })
    expect(describeInquiryBar(mallInquiryOf({ status: 'CLOSED', closeReason: 'CUSTOMER' }), [], false, NOW))
      .toEqual({ kind: 'REINQUIRE', text: '你已关闭这份询价' })
    expect(describeInquiryBar(mallInquiryOf({ quotingOpen: false }), [quoteOf({ choosable: false, status: 'EXPIRED' })], false, NOW).kind)
      .toBe('REINQUIRE')
  })

  it('explains choosing, closing and a stale choice', () => {
    const confirm = describeChoiceConfirm(quoteOf())
    expect(confirm.text).toContain('销售方为示例机器人商家')
    expect(confirm.rows.map((row) => row.value)).toEqual(['示例 M6 测绘版', '¥68,000 / 台', '¥136,000（2 台）', '5 天', '至 11/22 18:00'])
    expect(mallInquiryCloseConfirm({ quoteCount: 2 }).text).toBe('关闭后已收到的报价全部失效，商家不再报价。')
    expect(describeChoiceFailure({ status: 409, code: 'MALL_INQUIRY_QUOTE_CHANGED', message: '' }))
      .toEqual({ kind: 'RELOAD', message: '商家刚更新了报价，已刷新为最新报价，请再看一下' })
    expect(describeChoiceFailure({ status: 412, code: 'MALL_INQUIRY_VERSION_CONFLICT', message: '' }).kind).toBe('RELOAD')
    expect(describeChoiceFailure({ status: 409, code: 'MALL_INQUIRY_QUOTE_EXPIRED', message: '' }).kind).toBe('RELOAD')
    expect(describeChoiceFailure({ status: 401, code: null, message: '' })).toEqual({ kind: 'UNAUTHORIZED' })
    expect(describeChoiceFailure({ status: 0, code: null, message: '' }).kind).toBe('RETRY')
  })

  it('says why a quote cannot be ordered on the confirm page', () => {
    for (const code of ['MALL_INQUIRY_QUOTE_EXPIRED', 'MALL_INQUIRY_QUOTE_NOT_CHOSEN', 'TRADE_INQUIRY_QUOTE_ORDERED']) {
      expect(classifySubmitFailure({ status: 409, code, message: 'x' }).kind).toBe('QUOTE_UNAVAILABLE')
    }
    expect(classifySubmitFailure({ status: 409, code: 'TRADE_INQUIRY_QUOTE_ORDERED', message: 'x' }))
      .toEqual({ kind: 'QUOTE_UNAVAILABLE', message: '这份报价已经下过单，请回到报价比选查看订单' })
  })
})

describe('需求与报价 card of a goods inquiry', () => {
  it('shows the board lines and the buttons the status allows', () => {
    const quoted = describeMallInquiryCard(mallInquiryOf())
    expect(quoted).toMatchObject({
      kind: '询价单',
      publisher: '企业 · 东营示例物流公司',
      statusText: '报价中',
      tone: 'QUOTING',
      title: '行业测绘无人机',
      meta: '测绘版 · RTK · 五镜头倾斜相机 · 2 台',
      line: '已收到 2 份报价 · 截止 11/22 10:00',
    })
    expect(quoted.buttons.map((button) => [button.action, button.label, button.primary])).toEqual([
      ['CLOSE', '关闭询价', false],
      ['QUOTES', '比选报价', true],
    ])
    const chosen = {
      quoteId: 'q', quoteVersion: 1, offerId: 'o', merchant: { organizationId: 'm', name: '示例机器人商家', kind: 'FACTORY_DIRECT' as const },
      unitAmountMinor: 1, quantity: 2, totalMinor: 13_600_000, validUntil: '', chosenAt: '', orderId: 't-1',
    }
    const ordered = describeMallInquiryCard(mallInquiryOf({ status: 'CHOSEN', closable: false, chosen }))
    expect(ordered.line).toBe('已选定 示例机器人商家 · ¥136,000')
    expect(ordered.buttons.map((button) => button.action)).toEqual(['QUOTES', 'ORDER'])
    const closed = describeMallInquiryCard(mallInquiryOf({ status: 'CLOSED', closeReason: 'CUSTOMER', closable: false }))
    expect(closed.buttons.map((button) => button.label)).toEqual(['查看询价', '重新询价'])
    expect(closed.line).toBe('你已关闭这份询价')
    expect(describeMallInquiryCard(mallInquiryOf({ status: 'OPEN', quoteCount: 0 })).line).toBe('等待商家报价 · 截止 11/22 10:00')
  })
})

describe('delivery city name', () => {
  it('finds a city in the provinces, then in the hot cities', async () => {
    const { cityNameIn } = await import('../application/inquiry-view')
    const city = (code: string, name: string) => ({ code, name, pinyin: '', initials: '', provinceCode: '37', provinceName: '山东省' })
    const directory = {
      hotCities: [city('110100', '北京市')],
      provinces: [{ code: '37', name: '山东省', pinyin: '', initials: '', cities: [city('370500', '东营市')] }],
    }
    expect(cityNameIn(directory, '370500')).toBe('东营市')
    expect(cityNameIn(directory, '110100')).toBe('北京市')
    expect(cityNameIn(directory, '999999')).toBeNull()
  })
})
