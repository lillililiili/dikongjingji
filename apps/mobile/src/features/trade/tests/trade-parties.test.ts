import { describe, expect, it } from 'vitest'

import { aftersaleTerms, goodsParties, inquiryParties, rentalParties } from '../presentation/shared/trade-parties'

describe('交易主体 rows', () => {
  it('names the seller as the payee, through the platform channel (C2), and the warehouse when known', () => {
    expect(goodsParties({ merchantName: '示例机器人商家', merchantKindLabel: '厂家直营', fulfilledFrom: '示例机器人商家 东营仓' }))
      .toEqual([
        { label: '销售方', value: '示例机器人商家（厂家直营）', testId: undefined },
        { label: '收款方', value: '示例机器人商家 · 经平台支付渠道' },
        { label: '发货履约', value: '示例机器人商家 东营仓' },
      ])
    expect(goodsParties({ merchantName: '示例机器人商家', merchantKindLabel: null, fulfilledFrom: null }).map((row) => row.label))
      .toEqual(['销售方', '收款方'])
  })

  it('says 出租方 and 交接履约 for a rental', () => {
    expect(rentalParties({ merchantName: '示例机器人商家', merchantKindLabel: '厂家直营', fulfilledFrom: '示例机器人商家 东营仓' })
      .map((row) => row.label)).toEqual(['出租方', '收款方', '交接履约'])
  })

  it('leaves the seller of an inquiry product to the chosen quote', () => {
    expect(inquiryParties().map((row) => row.value)).toEqual(['你选定报价的商家', '销售方 · 经平台支付渠道', '销售方或其授权仓'])
  })

  it('has the five rows of the boards OrderGoods and PdFull once 开票方 and 售后责任 are known (S4-4)', () => {
    const rows = goodsParties({
      merchantName: '示例机器人商家',
      merchantKindLabel: '厂家直营',
      fulfilledFrom: '示例机器人商家 东营仓',
      invoiceIssuer: '示例机器人商家',
      aftersaleParty: '示例机器人商家 · 厂家保修',
    })
    expect(rows.map((row) => [row.label, row.value])).toEqual([
      ['销售方', '示例机器人商家（厂家直营）'],
      ['收款方', '示例机器人商家 · 经平台支付渠道'],
      ['开票方', '示例机器人商家'],
      ['发货履约', '示例机器人商家 东营仓'],
      ['售后责任', '示例机器人商家 · 厂家保修'],
    ])
    // A row the server leaves null is not shown.
    expect(goodsParties({ merchantName: '示例机器人商家', merchantKindLabel: null, fulfilledFrom: null, invoiceIssuer: '示例机器人商家', aftersaleParty: null })
      .map((row) => row.label)).toEqual(['销售方', '收款方', '开票方'])
  })

  it('writes the three after-sales terms of OrderGoods; no 七天无理由 when the product does not take it', () => {
    expect(aftersaleTerms({ noReasonReturn: null, aftersaleParty: '示例机器人商家 · 厂家保修' })).toEqual([
      '未拆封 7 天无理由退货（按销售方规则）',
      '保修由售后责任方负责（示例机器人商家 · 厂家保修）',
      '协商不成可联系平台客服协助沟通（平台不裁决）',
    ])
    expect(aftersaleTerms({ noReasonReturn: false, aftersaleParty: null })).toEqual([
      '保修由售后责任方负责',
      '协商不成可联系平台客服协助沟通（平台不裁决）',
    ])
  })
})
