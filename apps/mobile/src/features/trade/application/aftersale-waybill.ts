import type { AftersaleWaybill, ReturnShipmentInput } from '../domain/aftersale'

export const TRACKING_NO_PATTERN = /^[0-9A-Za-z-]{4,40}$/
export const CARRIER_NAME_LIMIT = 40

export interface WaybillDraft {
  carrierName: string
  trackingNo: string
}

export function initialWaybillDraft(waybill: AftersaleWaybill | null): WaybillDraft {
  return { carrierName: waybill?.carrierName ?? '', trackingNo: waybill?.trackingNo ?? '' }
}

export function waybillInput(draft: WaybillDraft): ReturnShipmentInput | null {
  const carrierName = draft.carrierName.trim()
  const trackingNo = draft.trackingNo.trim()
  if (!carrierName || carrierName.length > CARRIER_NAME_LIMIT) return null
  if (!TRACKING_NO_PATTERN.test(trackingNo)) return null
  return { carrierName, trackingNo }
}

// 输入框下面的提示；没有问题时为 null。
export function waybillHint(draft: WaybillDraft): string | null {
  const trackingNo = draft.trackingNo.trim()
  if (!draft.carrierName.trim()) return '请填写承运商名称'
  if (trackingNo && !TRACKING_NO_PATTERN.test(trackingNo)) return '运单号为 4–40 位字母、数字或“-”'
  return null
}
