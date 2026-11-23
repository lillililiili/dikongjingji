import {
  AFTERSALE_IMAGES_PER_SLOT,
  AFTERSALE_KINDS,
  type AftersaleEligibility,
  type AftersaleImageSlot,
  type AftersaleKind,
  type AftersaleReason,
  type ApplyAftersaleInput,
} from '../domain/aftersale'
import { AFTERSALE_REASON_LABELS, AFTERSALE_UNAVAILABLE_LABELS } from './aftersale-view'
import { createTradeKey } from './trade-keys'

/**
 * 申请售后页（画板 AftersaleApply）：能选哪类、哪个原因都由服务端的 eligibility 给出，
 * 页面只把不能选的灰掉、写上原因。
 */

export interface AftersaleKindChoice {
  kind: AftersaleKind
  title: string
  sub: string
  available: boolean
}

const KIND_TITLES: Record<AftersaleKind, string> = {
  REFUND_ONLY: '仅退款（未发货）',
  RETURN_REFUND: '退货退款',
}

const KIND_SUBS: Record<AftersaleKind, string> = {
  REFUND_ONLY: '商品还没发货，申请后全额原路退回',
  RETURN_REFUND: '寄回商品，商家确认收货后退款',
}

// 画板的顺序：仅退款在前，退货退款在后。服务端没给的一类不列。
export function kindChoices(eligibility: AftersaleEligibility): AftersaleKindChoice[] {
  return AFTERSALE_KINDS.flatMap((kind) => {
    const option = eligibility.kinds.find((item) => item.kind === kind)
    if (!option) return []
    return [{
      kind,
      title: KIND_TITLES[kind],
      sub: option.available
        ? KIND_SUBS[kind]
        : option.unavailableReason ? AFTERSALE_UNAVAILABLE_LABELS[option.unavailableReason] : '现在不可选',
      available: option.available,
    }]
  })
}

export interface AftersaleReasonChoice {
  reason: AftersaleReason
  label: string
  available: boolean
  // 不能选时的原因。
  note: string | null
}

// 选中那一类的原因。
export function reasonChoices(eligibility: AftersaleEligibility, kind: AftersaleKind | null): AftersaleReasonChoice[] {
  if (!kind) return []
  return eligibility.reasons
    .filter((option) => option.kind === kind)
    .map((option) => ({
      reason: option.reason,
      label: AFTERSALE_REASON_LABELS[option.reason],
      available: option.available,
      note: option.available
        ? null
        : option.unavailableReason ? AFTERSALE_UNAVAILABLE_LABELS[option.unavailableReason] : '现在不可选',
    }))
}

// 一开始选中第一个能选的类型和它第一个能选的原因；都不能选时为 null，提交按钮不可用。
export function initialChoice(eligibility: AftersaleEligibility): { kind: AftersaleKind | null; reason: AftersaleReason | null } {
  const kind = kindChoices(eligibility).find((choice) => choice.available)?.kind ?? null
  return { kind, reason: firstReason(eligibility, kind) }
}

export function firstReason(eligibility: AftersaleEligibility, kind: AftersaleKind | null): AftersaleReason | null {
  return reasonChoices(eligibility, kind).find((choice) => choice.available)?.reason ?? null
}

export function canChooseReason(eligibility: AftersaleEligibility, kind: AftersaleKind | null, reason: AftersaleReason): boolean {
  return reasonChoices(eligibility, kind).some((choice) => choice.reason === reason && choice.available)
}

// 申请金额：还能退的全部（设计假设 6；页面不让改，和画板一样只显示）。
export function applyAmountMinor(eligibility: AftersaleEligibility): number {
  return eligibility.maxRefundMinor
}

export interface UploadedEvidence {
  imageId: string
  slot: AftersaleImageSlot
  // 本地文件，上传前后都能直接显示，不用再带令牌读回来。
  localPath: string
}

export function slotCount(images: readonly UploadedEvidence[], slot: AftersaleImageSlot): number {
  return images.filter((image) => image.slot === slot).length
}

export function slotRoom(images: readonly UploadedEvidence[], slot: AftersaleImageSlot): number {
  return Math.max(0, AFTERSALE_IMAGES_PER_SLOT - slotCount(images, slot))
}

export function canSubmitApplication(input: {
  eligibility: AftersaleEligibility
  kind: AftersaleKind | null
  reason: AftersaleReason | null
  uploading: boolean
}): boolean {
  if (!input.kind || !input.reason || input.uploading) return false
  const kindOk = input.eligibility.kinds.some((option) => option.kind === input.kind && option.available)
  return kindOk && canChooseReason(input.eligibility, input.kind, input.reason) && input.eligibility.maxRefundMinor > 0
}

export function applicationInput(input: {
  kind: AftersaleKind
  reason: AftersaleReason
  description: string
  images: readonly UploadedEvidence[]
}): ApplyAftersaleInput {
  return {
    kind: input.kind,
    reason: input.reason,
    description: input.description.trim(),
    // 不传是还能退的全部；仅退款也只能全部。
    requestedMinor: null,
    imageIds: input.images.map((image) => image.imageId),
  }
}

/**
 * 同一份内容用同一个 Idempotency-Key：网络断了再点一次提交，服务端认出是同一张单；
 * 换了类型、原因、描述或图片就是另一份申请，换新键。
 */
export function createApplyKeyHolder(createKey: () => string = createTradeKey) {
  let signature: string | null = null
  let key = ''
  return {
    keyFor(orderId: string, input: ApplyAftersaleInput): string {
      const next = [orderId, input.kind, input.reason, input.description, String(input.requestedMinor), input.imageIds.join(',')].join('|')
      if (next !== signature) {
        signature = next
        key = createKey()
      }
      return key
    },
  }
}
