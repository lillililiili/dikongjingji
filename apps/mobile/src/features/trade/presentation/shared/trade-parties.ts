// 交易主体: who sells, who is paid, who invoices, who ships and who answers for after-sales, the same
// words on the product page, the confirm page and the order page (boards PdFull, Checkout, OrderGoods,
// PdRental, PdInquiryFull, OrderRental).
// C2 (2026-11-18): the money of every order goes straight to the merchant that sells it, through the
// platform's payment channel, so 收款方 is always the seller.
// S4-4 (2026-11-23, docs/redesign/09-商品售后与商家主页.md §3): 开票方 and 售后责任 come from the server —
// on the product page from the chosen offer (catalog 1.4.0: offer → merchant profile → merchant name),
// on the order from the snapshot taken when it was placed (trade 0.6.0 parties). A row the server
// leaves null is not shown; nothing is filled in here.

export interface TradePartyRow {
  label: string
  value: string
  /** Kept on the value so existing tests can still find it. */
  testId?: string
}

const CHANNEL = '经平台支付渠道'

// Board order: 销售方 / 收款方 / 开票方 / 发货履约 / 售后责任.
export function goodsParties(input: {
  merchantName: string
  merchantKindLabel: string | null
  fulfilledFrom: string | null
  invoiceIssuer?: string | null
  aftersaleParty?: string | null
  sellerTestId?: string
}): TradePartyRow[] {
  const seller = input.merchantKindLabel ? `${input.merchantName}（${input.merchantKindLabel}）` : input.merchantName
  const rows: TradePartyRow[] = [
    { label: '销售方', value: seller, testId: input.sellerTestId },
    { label: '收款方', value: `${input.merchantName} · ${CHANNEL}` },
  ]
  if (input.invoiceIssuer) rows.push({ label: '开票方', value: input.invoiceIssuer, testId: 'trade-party-invoice' })
  if (input.fulfilledFrom) rows.push({ label: '发货履约', value: input.fulfilledFrom })
  if (input.aftersaleParty) rows.push({ label: '售后责任', value: input.aftersaleParty, testId: 'trade-party-aftersale' })
  return rows
}

/**
 * 订单页的“售后条款”（画板 OrderGoods）：七天无理由（商品不支持时不写；订单上看不到这个标记时
 * 按画板写“按销售方规则”）、保修由售后责任方负责、协商不成可联系平台客服（平台不裁决）。
 */
export function aftersaleTerms(input: { noReasonReturn: boolean | null; aftersaleParty: string | null }): string[] {
  const terms: string[] = []
  if (input.noReasonReturn !== false) terms.push('未拆封 7 天无理由退货（按销售方规则）')
  terms.push(input.aftersaleParty ? `保修由售后责任方负责（${input.aftersaleParty}）` : '保修由售后责任方负责')
  terms.push('协商不成可联系平台客服协助沟通（平台不裁决）')
  return terms
}

export function rentalParties(input: {
  merchantName: string
  merchantKindLabel: string | null
  fulfilledFrom: string | null
  sellerTestId?: string
}): TradePartyRow[] {
  const lessor = input.merchantKindLabel ? `${input.merchantName}（${input.merchantKindLabel}）` : input.merchantName
  const rows: TradePartyRow[] = [
    { label: '出租方', value: lessor, testId: input.sellerTestId },
    { label: '收款方', value: `${input.merchantName} · ${CHANNEL}` },
  ]
  if (input.fulfilledFrom) rows.push({ label: '交接履约', value: input.fulfilledFrom })
  return rows
}

// An inquiry product has no seller until a quote is chosen (board PdInquiryFull).
export function inquiryParties(): TradePartyRow[] {
  return [
    { label: '销售方', value: '你选定报价的商家' },
    { label: '收款方', value: `销售方 · ${CHANNEL}` },
    { label: '发货履约', value: '销售方或其授权仓' },
  ]
}
