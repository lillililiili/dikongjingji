import type { MallSaleMode } from '../domain/mall'

export interface ProductAfterSalesLine {
  title: string
  text: string
}

/**
 * 商品详情的“售后与保障”（画板 PdFull：7 天无理由退货、保修、平台协助、发票）。
 * S4-2 / S4-4（2026-11-23）起七天无理由和售后责任方有了服务端事实：“7 天无理由退货”只在商品
 * noReasonReturn 为 true 时写（租赁不写）；“保修”只写由所选供给的售后责任方负责，不写年限和条件，
 * 那些要等商家自己填。“平台协助”“发票”两条照旧（R5 #8）。
 */
export function productAfterSales(input: {
  saleMode: MallSaleMode
  noReasonReturn: boolean
  aftersaleParty: string | null
}): ProductAfterSalesLine[] {
  const lines: ProductAfterSalesLine[] = []
  if (input.saleMode !== 'RENT' && input.noReasonReturn) {
    lines.push({ title: '7 天无理由退货', text: '未拆封，按销售方规则执行' })
  }
  if (input.saleMode !== 'RENT' && input.aftersaleParty) {
    lines.push({ title: '保修', text: `由售后责任方 ${input.aftersaleParty} 负责` })
  }
  lines.push({ title: '平台协助', text: '与商家协商不成时，可联系平台客服协助沟通' })
  lines.push({ title: '发票', text: `由${input.saleMode === 'RENT' ? '出租方' : '销售方'}开具，平台不代开` })
  return lines
}
