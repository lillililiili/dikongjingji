import type { AftersaleWaybill, ReturnShipmentInput } from '../domain/aftersale'

/**
 * 填写 / 修改退货运单的弹层（画板 AftersaleFlow 的 sheet）。真实承运商列表还没定，先用画板的两个胶囊：
 * “示例快递”和“其他承运商”；选“其他承运商”时手填名称。运单号按契约只收 4–40 位字母、数字和连字符。
 */
export const SAMPLE_CARRIER = '示例快递'
export type CarrierPick = 'SAMPLE' | 'OTHER'

export const TRACKING_NO_PATTERN = /^[0-9A-Za-z-]{4,40}$/
export const CARRIER_NAME_LIMIT = 40

export interface WaybillDraft {
  pick: CarrierPick
  otherCarrier: string
  trackingNo: string
}

// 修改运单时带出已提交的那一张；还没填过就是示例快递、空单号。
export function initialWaybillDraft(waybill: AftersaleWaybill | null): WaybillDraft {
  if (!waybill) return { pick: 'SAMPLE', otherCarrier: '', trackingNo: '' }
  return waybill.carrierName === SAMPLE_CARRIER
    ? { pick: 'SAMPLE', otherCarrier: '', trackingNo: waybill.trackingNo }
    : { pick: 'OTHER', otherCarrier: waybill.carrierName, trackingNo: waybill.trackingNo }
}

export function waybillInput(draft: WaybillDraft): ReturnShipmentInput | null {
  const carrierName = draft.pick === 'SAMPLE' ? SAMPLE_CARRIER : draft.otherCarrier.trim()
  const trackingNo = draft.trackingNo.trim()
  if (!carrierName || carrierName.length > CARRIER_NAME_LIMIT) return null
  if (!TRACKING_NO_PATTERN.test(trackingNo)) return null
  return { carrierName, trackingNo }
}

// 输入框下面的提示；没有问题时为 null。
export function waybillHint(draft: WaybillDraft): string | null {
  const trackingNo = draft.trackingNo.trim()
  if (draft.pick === 'OTHER' && !draft.otherCarrier.trim()) return '请填写承运商名称'
  if (trackingNo && !TRACKING_NO_PATTERN.test(trackingNo)) return '运单号为 4–40 位字母、数字或“-”'
  return null
}
