export const BOOKING_MAX_ATTACHMENTS = 9
export const BOOKING_AGREEMENT_VERSION = '2026-07'

export interface BookingCodeLabel {
  code: string
  label: string
}

export interface BookingServiceSnapshot {
  serviceId: string
  categoryName: string
  title: string
  summary: string
  coverImageUrl: string | null
  cityCode: string
  inspectionItems: readonly BookingCodeLabel[]
  priceLabel: string | null
}

export type BookingAttachmentStatus =
  | 'PENDING_UPLOAD'
  | 'UPLOADING'
  | 'SCANNING'
  | 'READY'
  | 'FAILED'

export interface BookingAttachment {
  localId: string
  filePath: string
  originalName: string
  contentType: string
  sizeBytes: number
  sha256: string
  status: BookingAttachmentStatus
  fileId?: string
  idempotencyKey?: string
  errorMessage?: string
}

export interface BookingDraft {
  locationAddress: string
  lineLengthKm: string
  preferredServiceDate: string
  inspectionItemCodes: string[]
  contactName: string
  contactMobile: string
  note: string
  agreementAccepted: boolean
  attachments: BookingAttachment[]
}

export interface CreateReservationPayload {
  serviceId: string
  cityCode: string
  location: { address: string }
  lineLengthMeters: number
  preferredServiceDate: string
  inspectionItemCodes: string[]
  attachmentFileIds: string[]
  note: string | null
  contact: { name: string; mobile: string }
  agreementVersion: string
  agreementAccepted: true
}

export function createEmptyBookingDraft(): BookingDraft {
  return {
    locationAddress: '',
    lineLengthKm: '',
    preferredServiceDate: '',
    inspectionItemCodes: [],
    contactName: '',
    contactMobile: '',
    note: '',
    agreementAccepted: false,
    attachments: [],
  }
}

export function validateBookingDraft(
  draft: BookingDraft,
  options: { allowPendingAttachments?: boolean } = {},
): string[] {
  const errors: string[] = []

  if (draft.locationAddress.trim().length < 2) {
    errors.push('请填写巡检地点')
  }

  if (!toLineLengthMeters(draft.lineLengthKm)) {
    errors.push('请填写有效的线路长度')
  }

  if (!/^\d{4}-\d{2}-\d{2}$/.test(draft.preferredServiceDate)) {
    errors.push('请选择期望巡检日期')
  }

  if (draft.inspectionItemCodes.length === 0) {
    errors.push('请至少选择一个巡检项目')
  }

  if (!draft.contactName.trim()) {
    errors.push('请填写联系人')
  }

  if (!/^1[3-9]\d{9}$/.test(draft.contactMobile.trim())) {
    errors.push('请填写正确的手机号')
  }

  if (!draft.agreementAccepted) {
    errors.push('请阅读并同意服务协议')
  }

  if (draft.attachments.length > BOOKING_MAX_ATTACHMENTS) {
    errors.push(`附件最多上传 ${BOOKING_MAX_ATTACHMENTS} 个`)
  }

  if (draft.attachments.some((attachment) => attachment.status === 'FAILED')) {
    errors.push('请重试上传失败的附件')
  }

  if (!options.allowPendingAttachments && draft.attachments.some((attachment) => attachment.status !== 'READY')) {
    errors.push('附件正在上传或扫描，请稍后提交')
  }

  return errors
}

export function createReservationPayload(
  service: BookingServiceSnapshot,
  draft: BookingDraft,
): CreateReservationPayload {
  const lineLengthMeters = toLineLengthMeters(draft.lineLengthKm)

  if (!lineLengthMeters) {
    throw new Error('line length is invalid')
  }

  return {
    serviceId: service.serviceId,
    cityCode: service.cityCode,
    location: { address: draft.locationAddress.trim() },
    lineLengthMeters,
    preferredServiceDate: draft.preferredServiceDate,
    inspectionItemCodes: [...new Set(draft.inspectionItemCodes)],
    attachmentFileIds: [],
    note: draft.note.trim() || null,
    contact: {
      name: draft.contactName.trim(),
      mobile: draft.contactMobile.trim(),
    },
    agreementVersion: BOOKING_AGREEMENT_VERSION,
    agreementAccepted: true,
  }
}

export function createBookingIdempotencyKey(
  random: () => string,
): string {
  return random()
}

function toLineLengthMeters(value: string): number | null {
  const kilometres = Number(value.trim())

  if (!Number.isFinite(kilometres) || kilometres <= 0) {
    return null
  }

  const metres = Math.round(kilometres * 1000)
  return metres <= 10_000_000 ? metres : null
}
