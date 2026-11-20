// 交易主体: who sells, who is paid, who ships, the same words on the product page, the confirm page
// and the order page (boards PdFull, Checkout, OrderGoods, PdRental, PdInquiryFull, OrderRental).
// C2 (2026-11-18): the money of every order goes straight to the merchant that sells it, through the
// platform's payment channel, so 收款方 is always the seller. Who invoices and who answers for
// after-sales are not settled yet, so those two rows of the boards are left out.

export interface TradePartyRow {
  label: string
  value: string
  /** Kept on the value so existing tests can still find it. */
  testId?: string
}

const CHANNEL = '经平台支付渠道'

export function goodsParties(input: {
  merchantName: string
  merchantKindLabel: string | null
  fulfilledFrom: string | null
  sellerTestId?: string
}): TradePartyRow[] {
  const seller = input.merchantKindLabel ? `${input.merchantName}（${input.merchantKindLabel}）` : input.merchantName
  const rows: TradePartyRow[] = [
    { label: '销售方', value: seller, testId: input.sellerTestId },
    { label: '收款方', value: `${input.merchantName} · ${CHANNEL}` },
  ]
  if (input.fulfilledFrom) rows.push({ label: '发货履约', value: input.fulfilledFrom })
  return rows
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
