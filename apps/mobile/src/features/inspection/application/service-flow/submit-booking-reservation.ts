import type { InspectionBookingApi } from '../../data/service-flow/inspection-booking-api'
import {
  createBookingUuid,
  type BookingFileHashPort,
} from './booking-crypto'
import {
  createBookingIdempotencyKey,
  createReservationPayload,
  validateBookingDraft,
  type BookingDraft,
  type BookingServiceSnapshot,
} from '../../domain/service-flow/booking'

export interface BookingSubmissionSession {
  idempotencyKey: string | null
  reservationId: string | null
}

export function createBookingSubmissionSession(): BookingSubmissionSession {
  return { idempotencyKey: null, reservationId: null }
}

export async function submitBookingReservation(
  api: InspectionBookingApi,
  session: BookingSubmissionSession,
  service: BookingServiceSnapshot,
  draft: BookingDraft,
  demandId?: string,
): Promise<{ reservationId: string }> {
  const errors = validateBookingDraft(draft, { allowPendingAttachments: true, categoryCode: service.categoryCode })

  if (errors.length > 0) {
    throw new Error(errors[0])
  }

  session.idempotencyKey ??= createBookingIdempotencyKey(createBookingUuid)
  if (!session.reservationId) {
    const reservation = await api.createReservation(
      createReservationPayload(service, draft),
      session.idempotencyKey,
      demandId,
    )
    session.reservationId = reservation.id
  }

  return { reservationId: session.reservationId }
}

export async function uploadReservationAttachments(
  api: InspectionBookingApi,
  session: BookingSubmissionSession,
  draft: BookingDraft,
  fileHashPort: BookingFileHashPort,
): Promise<boolean> {
  if (!session.reservationId) {
    throw new Error('预约尚未创建')
  }

  for (const attachment of draft.attachments) {
    if (attachment.status === 'READY') continue
    attachment.status = 'UPLOADING'
    attachment.errorMessage = undefined
    try {
      attachment.sha256 ||= await fileHashPort.sha256(attachment.filePath)
      attachment.idempotencyKey ??= createBookingUuid()
      const result = await api.uploadReservationAttachment({
        reservationId: session.reservationId,
        originalName: attachment.originalName,
        contentType: attachment.contentType,
        sizeBytes: attachment.sizeBytes,
        sha256: attachment.sha256,
        filePath: attachment.filePath,
        idempotencyKey: attachment.idempotencyKey,
      })
      attachment.fileId = result.fileId
      attachment.status = result.status === 'READY' ? 'READY' : result.status === 'REJECTED' ? 'FAILED' : 'SCANNING'
      attachment.errorMessage = result.errorMessage
    } catch (error) {
      attachment.status = 'FAILED'
      attachment.errorMessage = error instanceof Error ? error.message : '附件上传失败，请重试'
    }
  }

  for (let attempt = 0; attempt < 3 && draft.attachments.some((item) => item.status === 'SCANNING'); attempt += 1) {
    await new Promise<void>((resolve) => setTimeout(resolve, 400 * (attempt + 1)))
    const reservation = await api.getReservation(session.reservationId)
    for (const attachment of draft.attachments) {
      const serverFile = reservation.attachments.find((item) => item.id === attachment.fileId)
      if (!serverFile) continue
      attachment.status = serverFile.status === 'READY' ? 'READY' : serverFile.status === 'REJECTED' ? 'FAILED' : 'SCANNING'
      attachment.errorMessage = serverFile.scanFailureReason ?? undefined
    }
  }

  return draft.attachments.every((attachment) => attachment.status === 'READY')
}
