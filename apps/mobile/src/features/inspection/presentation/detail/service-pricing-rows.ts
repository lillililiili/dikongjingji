import type { InspectionServiceDetail } from '../../domain/inspection-service-detail'

export interface ServicePricingRow {
  code: 'PRICING' | 'FACTORS' | 'CANCELLATION'
  label: string
  value: string
}

/**
 * “价格与取消”卡的三行：计价方式、影响价格、取消说明。
 * 服务端没配置的行不出现；三行都没有时返回空数组，页面整卡隐藏。
 */
export function resolveServicePricingRows(
  detail: Pick<InspectionServiceDetail, 'priceNote' | 'priceFactors' | 'cancellationNote'>,
): ServicePricingRow[] {
  const rows: ServicePricingRow[] = []
  if (detail.priceNote.trim()) {
    rows.push({ code: 'PRICING', label: '计价方式', value: detail.priceNote.trim() })
  }
  const factors = detail.priceFactors.map((factor) => factor.trim()).filter(Boolean)
  if (factors.length) {
    rows.push({ code: 'FACTORS', label: '影响价格', value: factors.join('、') })
  }
  if (detail.cancellationNote.trim()) {
    rows.push({ code: 'CANCELLATION', label: '取消说明', value: detail.cancellationNote.trim() })
  }
  return rows
}
